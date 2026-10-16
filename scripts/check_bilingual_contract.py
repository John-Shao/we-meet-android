"""Check the Android/backend/gateway speech-language contract without credentials."""

import ast
import re
from pathlib import Path


def language_constant(path, name):
    tree = ast.parse(path.read_text(encoding="utf-8"))
    value = next(
        node.value
        for node in tree.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == name for target in node.targets)
    )
    # These declarations deliberately use only a string split wrapped in a set/tuple.
    # Parse the declaration, never import Django or execute repository expressions.
    assert isinstance(value, ast.Call) and len(value.args) == 1
    split = value.args[0]
    assert isinstance(split, ast.Call) and isinstance(split.func, ast.Attribute)
    assert split.func.attr == "split" and not split.args and not split.keywords
    return set(ast.literal_eval(split.func.value).split())


def main():
    android = Path(__file__).resolve().parents[1]
    server = android.parent / "we-meet"
    kotlin = (android / "app/src/main/java/com/we/meet/ui/ai/BilingualLanguages.kt").read_text(encoding="utf-8")
    client = set(re.findall(r'"([a-z]{2,3})"\s+to\s+R\.string\.', kotlin))
    backend = language_constant(server / "src/backend/core/api/assistant_translation.py", "LANGUAGES")
    gateway = language_constant(server / "src/agents/src/plugins/qwen/live_translate.py", "AUDIO_LANGUAGES")
    assert client == backend == gateway, {
        "client_only": sorted(client - backend),
        "backend_only": sorted(backend - client),
        "gateway_difference": sorted(gateway ^ backend),
    }
    assert len(client) == 29, "Review the product contract before changing supported languages"
    print("PASS: 29 speech languages match Android, backend and gateway")


if __name__ == "__main__":
    main()
