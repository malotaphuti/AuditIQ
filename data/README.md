# Pipeline data folder

Mounted into the analytics service at `/data`. Contents are git-ignored (synthetic, regenerable).

* `incoming/<folder>/transactions.csv` (+ optional `accounts.csv`, `merchants.csv`, `labels.csv`) – folders listed
  in the Data Pipeline page for **CSV folder** runs. Create one with:

  ```bash
  cd analytics-service
  python -m app.pipeline.cli --seed 42 --transactions 110000 --out ../data/incoming/sample
  ```

* `generated/<run-ref>/` – the raw feed produced by each **Synthetic generator** run, kept for traceability.

* `models/<version>.joblib` – trained Isolation Forest artifacts (one per model version, referenced by
  `ml_model_versions.artifact_path`). Retrain from **Reports → Train new model** if an artifact is removed.
