#!/usr/bin/env python3
"""Fetch pinned runtime/model assets, verify SHA-256, never execute downloaded files."""
from pathlib import Path
import hashlib, json, tarfile, tempfile, urllib.request
ROOT = Path(__file__).resolve().parents[1]
EXPECTED = json.loads((ROOT / 'DEPENDENCY_SHA256.json').read_text())
AAR = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar'
MODEL = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2'
def valid(path, sha):
    return path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == sha
def put(relative, data):
    assert hashlib.sha256(data).hexdigest() == EXPECTED[relative], 'SHA-256 mismatch: ' + relative
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.part')
    temp.write_bytes(data)
    temp.replace(path)
    print('Verified:', relative)
def main():
    missing = {r:s for r,s in EXPECTED.items() if not valid(ROOT/r,s)}
    if not missing:
        print('All assets already verified.'); return
    aar = 'app/libs/sherpa-onnx-2.0.1.aar'
    if aar in missing:
        with urllib.request.urlopen(AAR, timeout=120) as response: put(aar,response.read())
    model_files = [r for r in missing if r.startswith('app/src/main/assets/asr/')]
    if model_files:
        with tempfile.TemporaryDirectory() as tmp:
            archive=Path(tmp)/'model.tar.bz2'
            urllib.request.urlretrieve(MODEL,archive)
            with tarfile.open(archive,'r:bz2') as tar:
                # No extractall: only read the four exact expected basenames.
                for relative in model_files:
                    name=Path(relative).name
                    members=[m for m in tar.getmembers() if m.isfile() and Path(m.name).name==name]
                    assert len(members)==1, 'Missing or duplicate archive member: '+name
                    put(relative,tar.extractfile(members[0]).read())
    print('Ready. Build with ./gradlew :app:assembleDebug')
if __name__=='__main__': main()
