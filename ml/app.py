from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from ml.model import predict, train_model


@asynccontextmanager
async def lifespan(app):
    train_model()
    yield


app = FastAPI(title="Nexus inference worker", lifespan=lifespan, docs_url=None, redoc_url=None)


class DigitRequest(BaseModel):
    pixels: list[float] = Field(min_length=64, max_length=64)


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/predict")
def inference(request: DigitRequest):
    try:
        return predict(request.pixels)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
