import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('runtime', Path(__file__).resolve().parents[1] / 'tools/fetch-llama-runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

class DownloadTest(unittest.TestCase):
    def test_interruption_and_bad_cache_recover(self):
        good=b'verified test archive'
        with tempfile.TemporaryDirectory() as d, patch.object(runtime, 'DIGEST', hashlib.sha256(good).hexdigest()):
            target=Path(d)/'runtime.tar.gz'
            with patch.object(runtime.urllib.request, 'urlopen', side_effect=OSError('interrupted')):
                with self.assertRaises(OSError): runtime.verified_archive(target)
            self.assertEqual(list(Path(d).iterdir()), [])
            with patch.object(runtime.urllib.request, 'urlopen', return_value=io.BytesIO(b'truncated')):
                with self.assertRaises(RuntimeError): runtime.verified_archive(target)
            self.assertEqual(list(Path(d).iterdir()), [])
            target.write_bytes(b'old interrupted download')
            with patch.object(runtime.urllib.request, 'urlopen', return_value=io.BytesIO(good)):
                runtime.verified_archive(target)
            self.assertEqual(target.read_bytes(), good)
            with patch.object(runtime.urllib.request, 'urlopen', side_effect=AssertionError('no download')):
                runtime.verified_archive(target)

    def test_explicit_archive_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            target=Path(d)/'user.tar.gz';target.write_bytes(b'user bytes')
            with self.assertRaises(RuntimeError): runtime.verified_archive(target, supplied=True)
            self.assertEqual(target.read_bytes(), b'user bytes')

if __name__=='__main__': unittest.main()
