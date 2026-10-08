import importlib.util,json,os,pathlib,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('host',ROOT/'src-tauri/resources/host.py');host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
class DictionaryTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.old=os.environ.get('XDG_CONFIG_HOME');os.environ['XDG_CONFIG_HOME']=self.temp.name
 def tearDown(self):
  if self.old is None:os.environ.pop('XDG_CONFIG_HOME',None)
  else:os.environ['XDG_CONFIG_HOME']=self.old
  self.temp.cleanup()
 def test_cas_backup_and_user_private_permissions(self):
  d={'schema':1,'entries':{}}
  self.assertEqual(host.dictionary_read()['revision'],'')
  self.assertTrue(host.dictionary_write({'dictionary':d,'revision':''})['saved'])
  first=host.dictionary_read();self.assertFalse(host.dictionary_write({'dictionary':d,'revision':''})['saved'])
  self.assertTrue(host.dictionary_write({'dictionary':d,'revision':first['revision']})['saved'])
  for name in ['dictionary.json','dictionary.json.bak','dictionary.lock']:self.assertEqual((host.config_dir()/name).stat().st_mode&0o777,0o600)
 def test_corrupt_remote_is_not_overwritten(self):
  host.dictionary_write({'dictionary':{'schema':1,'entries':{}},'revision':''});path=host.config_dir()/'dictionary.json';path.write_text('broken')
  with self.assertRaises(ValueError):host.dictionary_write({'dictionary':{'schema':1,'entries':{}},'revision':''})
  self.assertEqual(path.read_text(),'broken')
 def test_refuses_symlink_primary_backup_and_lock(self):
  folder=host.config_dir();folder.mkdir(parents=True,exist_ok=True)
  target=pathlib.Path(self.temp.name)/'unrelated';target.write_text('preserve me')
  for name in ['dictionary.json','dictionary.json.bak','dictionary.lock']:
   path=folder/name;path.symlink_to(target)
   with self.assertRaises(ValueError):host.dictionary_write({'dictionary':{'schema':1,'entries':{}},'revision':''})
   self.assertEqual(target.read_text(),'preserve me');path.unlink()
 def test_invalid_dictionary_rejected(self):
  with self.assertRaises(ValueError):host.dictionary_write({'dictionary':{'schema':2,'entries':{}},'revision':''})
if __name__=='__main__':unittest.main()
