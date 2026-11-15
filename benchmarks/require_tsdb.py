import pathlib
import sys

data = pathlib.Path("demo/docker/prometheus/data")
ready = data.is_dir() and any(
    child.is_dir() and child.name.startswith("01") for child in data.iterdir()
)
if not ready:
    print(
        "Сначала снимите базу метрик, запустив демо (make demo-up) и дождавшись k6.",
        file=sys.stderr,
    )
    sys.exit(1)
