import importlib.util
import pathlib
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('viewer_host', ROOT / 'src-tauri/resources/host.py')
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)

class FileViewerTests(unittest.TestCase):
    def test_source_viewing_preserves_text_and_cannot_expand_edit_scope(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'test.py'
            content = b'# caf\xc3\xa9\r\nprint("hello")\r\n'
            path.write_bytes(content)
            result = host.file_read({'path': str(path)})
            self.assertEqual(result['text'].encode(), content)
            self.assertFalse(result['writable'])
            with self.assertRaises(ValueError):
                host.document_save({'path': str(path), 'revision': result['revision'], 'text': 'overwrite'})
            self.assertEqual(path.read_bytes(), content)
            rules = path.with_name('AGENTS.md')
            rules.write_text('# Rules')
            self.assertTrue(host.file_read({'path': str(rules)})['writable'])

    def test_rejects_binary_oversize_non_utf8_and_non_files(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'source'
            for content in [b'a\x00b', b'\xff', b'x' * (host.MAX_DOCUMENT + 1)]:
                path.write_bytes(content)
                with self.assertRaises(ValueError): host.file_read({'path': str(path)})
            with self.assertRaises(ValueError): host.file_read({'path': folder})
            with self.assertRaises(ValueError): host.file_read({'path': '/dev/zero'})
            path.write_bytes(b'')
            self.assertEqual(host.file_read({'path': str(path)})['text'], '')

if __name__ == '__main__': unittest.main()

class ImageViewerTests(unittest.TestCase):
    def test_chunk_roundtrip_and_revision_change(self):
        import base64
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "it's image.png"
            data = b'\x89PNG\r\n\x1a\n' + b'\x00' * 60000
            path.write_bytes(data)
            first = host.image_read({'path':str(path)})
            second = host.image_read({'path':str(path),'offset':host.IMAGE_CHUNK,'revision':first['revision']})
            self.assertEqual(first['mime'], 'image/png')
            self.assertEqual(base64.b64decode(first['data'])+base64.b64decode(second['data']),data)
            path.write_bytes(data+b'x')
            with self.assertRaisesRegex(ValueError,'changed'):
                host.image_read({'path':str(path),'revision':first['revision']})

    def test_rejects_nonimages_nonfiles_and_oversize(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / 'pretend.png'
            path.write_bytes(b'<svg><script>bad</script></svg>')
            with self.assertRaises(ValueError): host.image_read({'path':str(path)})
            with self.assertRaises(ValueError): host.image_read({'path':folder})
            with self.assertRaises(ValueError): host.image_read({'path':'/dev/zero'})
            with path.open('wb') as file: file.truncate(host.MAX_IMAGE+1)
            with self.assertRaises(ValueError): host.image_read({'path':str(path)})

class RelativeViewerPathTests(unittest.TestCase):
    def test_workspace_precedence_home_fallback_and_explicit_dot_paths(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            home = pathlib.Path(folder)
            workspace = home / 'workspace'
            workspace.mkdir()
            (home / 'dev').mkdir()
            (home / 'dev/preview.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'fixture')
            (home / 'preview.html').write_text('<h1>home</h1>')
            (workspace / 'preview.html').write_text('<h1>workspace</h1>')
            with patch.object(host.P, 'home', return_value=home):
                args = {'path':'preview.html', 'directory':str(workspace)}
                self.assertEqual(host.file_read(args)['text'], '<h1>workspace</h1>')
                result = host.image_read({'path':'dev/preview.png', 'directory':str(workspace)})
                self.assertEqual(result['path'], str(home / 'dev/preview.png'))
                self.assertEqual(host.file_read({'path':'../preview.html','directory':str(workspace)})['text'], '<h1>home</h1>')
                with self.assertRaises(FileNotFoundError):
                    host.image_read({'path':'./dev/preview.png','directory':str(workspace)})
