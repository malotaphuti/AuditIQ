"""Command-line export of the synthetic dataset.

    python -m app.pipeline.cli --seed 42 --transactions 110000 --out data/incoming/sample

The output folder (transactions.csv, accounts.csv, merchants.csv, labels.csv) can then be ingested through the
application with source = CSV and folder = sample.
"""

import argparse
import json
from pathlib import Path

from app.pipeline.generator import generate_dataset


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Generate the AuditIQ synthetic banking dataset")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--transactions", type=int, default=110_000)
    parser.add_argument("--out", type=Path, default=Path("data/incoming/sample"))
    args = parser.parse_args(argv)

    dataset = generate_dataset(seed=args.seed, n_transactions=args.transactions)
    folder = dataset.write_csv(args.out)
    print(f"Wrote {len(dataset.transactions):,} raw rows to {folder}")
    print(json.dumps(dataset.stats, indent=2))


if __name__ == "__main__":
    main()
