"""Train a small, deterministic model on the built-in handwritten digits dataset."""
from functools import lru_cache
import numpy as np
from sklearn.datasets import load_digits
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, f1_score, confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler


@lru_cache(maxsize=1)
def train_model():
    data = load_digits()
    x_train, x_test, y_train, y_test = train_test_split(
        data.data, data.target, test_size=0.25, random_state=42, stratify=data.target
    )
    model = make_pipeline(StandardScaler(), LogisticRegression(max_iter=1000, random_state=42))
    model.fit(x_train, y_train)
    pred = model.predict(x_test)
    # The holdout is not used for fitting or hyperparameter selection.
    report = {
        "dataset": "scikit-learn digits",
        "train_samples": len(x_train), "test_samples": len(x_test),
        "accuracy": float(accuracy_score(y_test, pred)),
        "macro_f1": float(f1_score(y_test, pred, average="macro")),
        "majority_baseline_accuracy": float(np.mean(y_test == np.bincount(y_train).argmax())),
        "confusion_matrix": confusion_matrix(y_test, pred, labels=range(10)).tolist(),
        "random_seed": 42,
    }
    return model, report


def predict(pixels):
    values = np.asarray(pixels, dtype=float)
    if values.shape != (64,) or not np.isfinite(values).all() or (values < 0).any() or (values > 16).any():
        raise ValueError("expected 64 finite pixel values in [0, 16]")
    model, _ = train_model()
    probabilities = model.predict_proba(values.reshape(1, -1))[0]
    best = int(np.argmax(probabilities))
    return {"digit": int(model.classes_[best]), "confidence": float(probabilities[best]),
            "model": "digits-logreg-v1"}


if __name__ == "__main__":
    import json
    print(json.dumps(train_model()[1], indent=2))
