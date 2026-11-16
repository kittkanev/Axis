import audioop
import io
import json
import runpy
import secrets
import subprocess
import sys
import tempfile
import threading
import wave
from pathlib import Path


helpers = runpy.run_path(str(Path(__file__).with_name("smoke-api.py")))
call = helpers["call"]


def memory_used():
    return int(subprocess.check_output(["free", "-m"]).splitlines()[1].split()[2])


def verify(token):
    sample = "Привет, это тест голосового помощника."
    call("POST", "/voice/tts", 401, {"text": sample})
    output = call("POST", "/voice/tts", 200, {"text": sample}, token, binary=True)
    with wave.open(io.BytesIO(output), "rb") as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 2 and wav.getnframes() > 1000
        assert wav.getnframes() < wav.getframerate() * 20
        frames = wav.readframes(wav.getnframes())
        assert audioop.rms(frames, 2) > 100
        print(f"Piper produced {len(frames)} PCM bytes at {wav.getframerate()} Hz")
        audio, _ = audioop.ratecv(frames, 2, 1, wav.getframerate(), 16000, None)
    if "--stt" not in sys.argv:
        return
    with tempfile.NamedTemporaryFile(suffix=".wav") as raw:
        with wave.open(raw.name, "wb") as wav:
            wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(audio)
        settings = ('header = "Content-Type: audio/wav"\nheader = '
                    + json.dumps("Authorization: Bearer " + token)
                    + "\ndata-binary = " + json.dumps("@" + raw.name))
        result = subprocess.run(["curl", "--noproxy", "*", "--silent", "--show-error", "--fail-with-body",
                                 "--max-time", "30", "--cacert", helpers["CA"], "--config", "-",
                                 helpers["BASE"] + "/voice/stt"], input=settings.encode(), capture_output=True, check=True)
        text = json.loads(result.stdout)["text"]
        assert "привет" in text.lower() and "тест" in text.lower(), "Russian recognition did not match the test phrase"
        print("POST /voice/stt -> 200, Vosk recognized the Russian test phrase")


credentials = {"email": f"voice-{secrets.token_hex(5)}@axis.invalid", "password": secrets.token_urlsafe(24)}
token = call("POST", "/auth/register", 201, credentials)["access_token"]
measured = [memory_used()]
stop = threading.Event()


def sample_memory():
    while not stop.wait(0.12):
        measured.append(memory_used())


sampler = threading.Thread(target=sample_memory)
sampler.start()
try:
    verify(token)
finally:
    stop.set()
    sampler.join()
    print(f"Sampled used memory during voice requests: peak {max(measured)} MiB")
