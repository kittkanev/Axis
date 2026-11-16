import io
import json
import os
import threading
import wave
from contextlib import contextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool

from axis_voice_proxy import MAX_WAV, SpeechInput


class VoiceWorker:
    def __init__(self):
        self.slot = threading.BoundedSemaphore(1)
        self.capacity = threading.BoundedSemaphore(2)

    @contextmanager
    def job(self):
        if not self.capacity.acquire(blocking=False):
            raise HTTPException(429, "voice queue is full")
        try:
            if not self.slot.acquire(timeout=8):
                raise HTTPException(429, "voice engine is busy")
            try:
                yield
            finally:
                self.slot.release()
        finally:
            self.capacity.release()


def create_app():
    engine = os.environ["AXIS_VOICE_ENGINE"]
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
    worker = VoiceWorker()

    if engine == "piper":
        import onnxruntime as ort
        from piper import PiperVoice
        from piper.config import PiperConfig

        model_path = "/opt/axis-voice/models/ru_RU-irina-medium.onnx"
        with open(model_path + ".json", encoding="utf-8") as model_config:
            config = PiperConfig.from_dict(json.load(model_config))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        options.inter_op_num_threads = 1
        options.enable_cpu_mem_arena = False
        options.enable_mem_pattern = False
        session = ort.InferenceSession(model_path, sess_options=options, providers=["CPUExecutionProvider"])
        voice = PiperVoice(session=session, config=config)

        @app.post("/tts")
        def synthesize(input: SpeechInput):
            text = input.text.strip()
            if not text:
                raise HTTPException(422, "text is empty")
            with worker.job():
                buffer = io.BytesIO()
                with wave.open(buffer, "wb") as wav:
                    voice.synthesize_wav(text, wav)
                return Response(buffer.getvalue(), media_type="audio/wav")

    elif engine == "vosk":
        from vosk import KaldiRecognizer, Model, SetLogLevel

        SetLogLevel(-1)
        model = Model("/opt/axis-voice/models/vosk-model-small-ru-0.22")

        def recognize(data):
            with worker.job():
                try:
                    with wave.open(io.BytesIO(data), "rb") as wav:
                        if (wav.getnchannels(), wav.getsampwidth(), wav.getframerate(), wav.getcomptype()) != (1, 2, 16000, "NONE"):
                            raise HTTPException(422, "expected mono PCM WAV, 16 bit and 16000 Hz")
                        if not 0 < wav.getnframes() <= 20 * 16000:
                            raise HTTPException(422, "audio duration must be at most 20 seconds")
                        recognizer = KaldiRecognizer(model, 16000)
                        phrases = []
                        while chunk := wav.readframes(4096):
                            if recognizer.AcceptWaveform(chunk):
                                phrases.append(json.loads(recognizer.Result()).get("text", ""))
                        phrases.append(json.loads(recognizer.FinalResult()).get("text", ""))
                except (wave.Error, EOFError):
                    raise HTTPException(422, "invalid WAV file") from None
                return {"text": " ".join(phrase for phrase in phrases if phrase)}

        @app.post("/stt")
        async def transcribe(request: Request):
            payload = bytearray()
            async for chunk in request.stream():
                payload.extend(chunk)
                if len(payload) > MAX_WAV:
                    raise HTTPException(413, "audio is too large")
            return await run_in_threadpool(recognize, payload)
    else:
        raise ValueError("Unknown voice engine")

    @app.get("/healthz")
    def health():
        return {"status": "ok", "engine": engine}

    return app
