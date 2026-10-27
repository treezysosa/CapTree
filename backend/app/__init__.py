import os

# Model downloads are explicit functionality; optional hub telemetry is off by default.
os.environ.setdefault("HF_HUB_DISABLE_TELEMETRY", "1")
