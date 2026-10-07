import importlib.util, pathlib, subprocess, tempfile, unittest, os
from unittest.mock import patch
ROOT=pathlib.Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('host',ROOT/'src-tauri/resources/host.py')
host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
class RepositoryTests(unittest.TestCase):
 def test_repeated_depth_warnings_are_collapsed(self):
  with tempfile.TemporaryDirectory() as temp:
   for i in range(25):
    (pathlib.Path(temp)/str(i)/'a/b/c/d/e/f').mkdir(parents=True)
   warnings=[]
   self.assertEqual(host.repos([temp,temp], warnings), [])
   self.assertEqual(len(warnings),1)
   self.assertIn('depth 5',warnings[0])
 def test_linked_worktrees_dates_and_ignored_files(self):
  with tempfile.TemporaryDirectory() as temp:
   root=pathlib.Path(temp);repo=root/'repo';tree=root/'tree with spaces';repo.mkdir()
   def git(*args): subprocess.run(['git','-C',str(repo),*args],check=True,capture_output=True)
   git('init');git('config','user.name','Test');git('config','user.email','test@example.invalid')
   (repo/'tracked').write_text('first');(repo/'.gitignore').write_text('ignored\n')
   git('add','.');git('commit','-m','fixture');git('worktree','add','-b','feature',str(tree))
   for location in [repo,tree]:
    for name in ['tracked','.gitignore']: os.utime(location/name,(1000000000,1000000000))
   (tree/'untracked').write_text('new');os.utime(tree/'untracked',(1700000000,1700000000))
   (tree/'ignored').write_text('excluded');os.utime(tree/'ignored',(1800000000,1800000000))
   result=host.repository_worktrees([str(tree),str(repo)])
   self.assertEqual(result['warnings'],[]);self.assertEqual(len(result['groups']),1)
   entries={t['path']:t for t in result['groups'][0]['worktrees']}
   self.assertEqual(entries[str(tree)]['modifiedAt'],1700000000)
   self.assertEqual(entries[str(repo)]['modifiedAt'],1000000000)
   self.assertEqual(entries[str(tree)]['branch'],'feature')
   self.assertFalse(entries[str(tree)]['partial'])
 def test_missing_repository_reports_failure(self):
  result=host.repository_worktrees(['/not/a/repository'])
  self.assertEqual(result['groups'],[]);self.assertEqual(len(result['warnings']),1)
if __name__=='__main__':unittest.main()
