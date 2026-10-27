"""am instrument can exit zero on a failed test; require an actual one-test success."""
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text(errors="replace")
assert re.search(r"OK \(1 test\)", text), text[-8000:]
assert "FAILURES!!!" not in text and "INSTRUMENTATION_FAILED" not in text, text[-8000:]
