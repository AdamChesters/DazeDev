from unittest.mock import patch
from types import SimpleNamespace
import base64, importlib.util, os, pathlib, tempfile, unittest
ROOT = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('upload_host', ROOT/'src-tauri/resources/host.py')
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)

class UploadSpaceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        self.environment = patch.dict(os.environ, {'XDG_DATA_HOME': str(self.root/'data')})
        self.environment.start()
    def tearDown(self):
        host.cleanup_uploads()
        self.environment.stop()
        self.temp.cleanup()
    def args(self, size=20, name='sample.bin', **extra):
        return dict(directory=str(self.root), name=name, size=size, **extra)
    def disk(self, free):
        return patch.object(host.shutil, 'disk_usage', return_value=SimpleNamespace(free=free))
    def test_preflight_and_begin_refuse_low_space_before_staging(self):
        with self.disk(host.UPLOAD_HEADROOM+19):
            for method in (host.upload_preflight, host.upload_begin):
                with self.assertRaisesRegex(ValueError, 'Not enough free space'):
                    method(self.args())
        self.assertEqual(list(self.root.iterdir()), [])
        self.assertEqual(host.UPLOADS, {})
    def test_preflight_canonical_path_values_and_begin_rechecks(self):
        with self.disk(host.UPLOAD_HEADROOM+20):
            result = host.dispatch('upload_preflight', self.args())
        self.assertEqual(result, dict(directory=str(self.root.resolve()), maxBytes=host.MAX_UPLOAD,
            freeBytes=host.UPLOAD_HEADROOM+20, availableBytes=host.UPLOAD_HEADROOM+20,
            requiredBytes=host.UPLOAD_HEADROOM+20, reservedBytes=0))
        with self.disk(host.UPLOAD_HEADROOM+19):
            with self.assertRaises(ValueError): host.upload_begin(self.args())
    def test_sizes_are_strict_and_paste_has_lower_limit(self):
        for size in (True, False, -1, 1.2, '2', None, host.MAX_UPLOAD+1):
            with self.assertRaises(ValueError): host.upload_preflight(self.args(size=size))
        with self.assertRaisesRegex(ValueError, 'use Upload'):
            host.upload_begin(self.args(size=host.MAX_PASTE_UPLOAD+1, paste=True))
        with self.disk(host.UPLOAD_HEADROOM+host.MAX_UPLOAD):
            self.assertEqual(host.upload_preflight(self.args(size=host.MAX_UPLOAD))['maxBytes'], host.MAX_UPLOAD)
        self.assertEqual(host.UPLOADS, {})
    def test_other_same_filesystem_transfers_reserve_unwritten_bytes(self):
        with self.disk(host.UPLOAD_HEADROOM+35):
            first = host.upload_begin(self.args())
            with self.assertRaises(ValueError): host.upload_begin(self.args(name='second'))
            item = host.UPLOADS[first['id']]
            item['offset'] = 5
            result = host.upload_preflight(self.args())
            self.assertEqual(result['reservedBytes'], 15)
            item['offset'] = 0
            host.upload_abort(first)
            self.assertEqual(host.upload_preflight(self.args())['reservedBytes'], 0)
    def test_chunks_recheck_free_space_without_writing_or_advancing(self):
        item = host.upload_begin(self.args())
        with self.disk(host.UPLOAD_HEADROOM+19):
            with self.assertRaises(ValueError):
                host.upload_chunk(dict(id=item['id'], offset=0, data=base64.b64encode(b'a').decode()))
        self.assertEqual(host.UPLOADS[item['id']]['offset'], 0)
        self.assertEqual(host.UPLOADS[item['id']]['path'].stat().st_size, 0)
        with self.disk(host.UPLOAD_HEADROOM+20):
            host.upload_chunk(dict(id=item['id'], offset=0, data=base64.b64encode(b'a'*20).decode()))
        host.upload_finish(item)
    def test_cache_names_unique_and_completed_files_survive_cleanup_and_reload(self):
        args = self.args(size=3, name='shot.png', paste=True)
        one = host.upload_begin(args)
        two = host.upload_begin(args)
        self.assertNotEqual(one['path'], two['path'])
        self.assertEqual(pathlib.Path(one['path']).parent, self.root/'data/dazedev/uploads')
        host.upload_chunk(dict(id=one['id'], offset=0, data=base64.b64encode(b'png').decode()))
        completed = host.upload_finish(one)
        host.cleanup_uploads()
        self.assertEqual(pathlib.Path(completed['path']).read_bytes(), b'png')
        reloaded = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(reloaded)
        reloaded.cleanup_uploads()
        self.assertEqual(pathlib.Path(completed['path']).read_bytes(), b'png')
        self.assertEqual(list(pathlib.Path(completed['path']).parent.glob('.dazedev-upload-*')), [])
    def test_expiry_only_removes_incomplete_owned_upload(self):
        item = host.upload_begin(self.args(size=0))
        host.UPLOADS[item['id']]['touched'] -= 1900
        existing = self.root/'previous-attachment.png'
        existing.write_bytes(b'keep')
        host.upload_preflight(self.args(size=0))
        self.assertNotIn(item['id'], host.UPLOADS)
        self.assertEqual(existing.read_bytes(), b'keep')
    def test_existing_file_symlink_and_publish_race_remain_protected(self):
        (self.root/'existing').write_bytes(b'original')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            host.upload_begin(self.args(size=0, name='existing'))
        (self.root/'link').symlink_to(self.root/'missing')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            host.upload_begin(self.args(size=0, name='link'))
        item = host.upload_begin(self.args(size=0))
        pathlib.Path(item['path']).write_bytes(b'concurrent')
        with self.assertRaises(FileExistsError): host.upload_finish(item)
        self.assertEqual(pathlib.Path(item['path']).read_bytes(), b'concurrent')

if __name__ == '__main__': unittest.main()

class OutOfOrderUploadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temp.name)
        host.BINARY = True
    def tearDown(self):
        host.BINARY = False
        host.cleanup_uploads()
        self.temp.cleanup()
    def chunk(self, key, offset, data):
        return host.upload_chunk({'id': key, 'offset': offset, 'data': base64.b64encode(data).decode()})
    def test_chunks_in_any_order_build_the_file_and_overlaps_are_refused(self):
        data = bytes(range(256)) * 40
        begun = host.upload_begin(dict(directory=str(self.root), name='out.bin', size=len(data)))
        self.assertEqual(begun['parallel'], host.UPLOAD_PARALLEL)
        key = begun['id']
        parts = [(0, data[:4000]), (8000, data[8000:]), (4000, data[4000:8000])]
        replies = [self.chunk(key, offset, part) for offset, part in [parts[1], parts[0], parts[2]]]
        self.assertEqual([r['received'] for r in replies], [len(data) - 8000, len(data) - 4000, len(data)])
        self.assertEqual(replies[1]['offset'], 4000)
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.chunk(key, 100, b'x')
        finished = host.upload_finish({'id': key})
        self.assertEqual(pathlib.Path(finished['path']).read_bytes(), data)
    def test_incomplete_upload_cannot_finish(self):
        begun = host.upload_begin(dict(directory=str(self.root), name='gap.bin', size=10))
        self.chunk(begun['id'], 5, b'12345')
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            host.upload_finish({'id': begun['id']})
    def test_terminal_protocol_offers_no_parallel_chunks(self):
        host.BINARY = False
        self.assertNotIn('parallel', host.upload_begin(dict(directory=str(self.root), name='seq.bin', size=1)))
