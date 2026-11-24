import math
import pytest
from fastapi.testclient import TestClient
from sklearn.datasets import load_digits
from ml.app import app
from ml.model import predict, train_model


def test_real_holdout_and_baseline():
    _, report = train_model()
    assert report["train_samples"] + report["test_samples"] == 1797
    assert report["accuracy"] > report["majority_baseline_accuracy"]
    assert report["macro_f1"] > 0.8


@pytest.mark.parametrize("pixels", [[0]*63, [17]*64, [-1]*64, [math.nan]*64])
def test_invalid_pixels(pixels):
    with pytest.raises(ValueError):
        predict(pixels)


def test_inference_contract():
    sample = load_digits().data[0].tolist()
    with TestClient(app) as client:
        assert client.get("/health").json() == {"status": "ok"}
        result = client.post("/predict", json={"pixels": sample})
        assert result.status_code == 200
        assert 0 <= result.json()["digit"] <= 9
        assert 0 <= result.json()["confidence"] <= 1
        assert client.post("/predict", json={"pixels": [0]}).status_code == 422
