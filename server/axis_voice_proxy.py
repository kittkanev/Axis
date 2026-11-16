import httpx
from fastapi import Depends, HTTPException, Request
from fastapi.responses import Response
from pydantic import BaseModel, Field


MAX_WAV = 640000 + 4096
PIPER = "http://127.0.0.1:2587"
VOSK = "http://127.0.0.1:2588"


class SpeechInput(BaseModel):
    text: str = Field(min_length=1, max_length=600)


class VoiceProxy:
    def __init__(self, limiter):
        self.limiter = limiter

    async def call(self, account, url, **kwargs):
        self.limiter.check(account)
        try:
            async with httpx.AsyncClient(timeout=httpx.Timeout(35, connect=2), trust_env=False) as client:
                response = await client.post(url, **kwargs)
        except httpx.HTTPError:
            raise HTTPException(503, "voice engine unavailable") from None
        if response.status_code in {422, 429}:
            raise HTTPException(response.status_code, response.json().get("detail", "voice engine rejected request"))
        if response.status_code != 200:
            raise HTTPException(503, "voice engine unavailable")
        return response

    def register(self, app, account_for):
        @app.post("/voice/tts")
        async def synthesize(payload: SpeechInput, account: str = Depends(account_for)):
            reply = await self.call(account, PIPER + "/tts", json=payload.model_dump())
            return Response(reply.content, media_type="audio/wav", headers={"Cache-Control": "no-store"})

        @app.post("/voice/stt")
        async def transcribe(request: Request, account: str = Depends(account_for)):
            if request.headers.get("content-type", "").split(";")[0] not in {"audio/wav", "audio/x-wav"}:
                raise HTTPException(415, "send a WAV file")
            reply = await self.call(account, VOSK + "/stt", content=await request.body())
            return Response(reply.content, media_type="application/json", headers={"Cache-Control": "no-store"})
