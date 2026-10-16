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
 def worktree_repo(self,temp,script=True):
  root=pathlib.Path(temp);repo=root/'repo';repo.mkdir();marker=root/'script-ran'
  def git(*args): subprocess.run(['git','-C',str(repo),*args],check=True,capture_output=True)
  git('init');git('config','user.name','Test');git('config','user.email','test@example.invalid');git('commit','--allow-empty','-m','fixture')
  if script:
   (repo/'scripts').mkdir();(repo/'scripts/new-worktree.sh').write_text('#!/bin/sh\nprintf "%s" "$1" > '+repr(str(marker))+'\n')
  return root,repo,marker
 def branches(self,repo):
  return subprocess.run(['git','-C',str(repo),'branch','--format=%(refname:short)'],capture_output=True,text=True,check=True).stdout.split()
 def test_worktree_script_needs_confirmation_then_runs_only_when_asked(self):
  with tempfile.TemporaryDirectory() as temp:
   root,repo,marker=self.worktree_repo(temp);args={'path':str(repo),'branch':'feature','destination':str(root/'tree')}
   for value in [None,'yes',1]:
    request=dict(args) if value is None else dict(args,runScript=value)
    self.assertEqual(host.dispatch('worktree',request),{'needsConfirmation':True,'script':'scripts/new-worktree.sh'})
   self.assertFalse(marker.exists());self.assertNotIn('feature',self.branches(repo));self.assertFalse((root/'tree').exists())
   host.dispatch('worktree',dict(args,runScript=True))
   self.assertEqual(marker.read_text(),'feature');self.assertFalse((root/'tree').exists())
   marker.unlink();result=host.dispatch('worktree',dict(args,branch='plain',destination=str(root/'plain'),runScript=False))
   self.assertFalse(marker.exists());self.assertTrue((root/'plain').is_dir());self.assertIn('plain',self.branches(repo))
   self.assertEqual(result['path'],str(repo))
 def test_worktree_without_script_uses_git(self):
  with tempfile.TemporaryDirectory() as temp:
   root,repo,marker=self.worktree_repo(temp,script=False)
   host.dispatch('worktree',{'path':str(repo),'branch':'direct','destination':str(root/'direct')})
   self.assertTrue((root/'direct').is_dir());self.assertIn('direct',self.branches(repo))
 def test_worktree_rejects_option_like_branch_and_destination(self):
  with tempfile.TemporaryDirectory() as temp:
   root,repo,marker=self.worktree_repo(temp)
   for args in [{'branch':'-x','destination':str(root/'a')},{'branch':'--force','destination':str(root/'a')},{'branch':'ok','destination':'--detach'},{'branch':'ok','destination':'-b'}]:
    for run_script in [True,False,None]:
     request=dict(args,path=str(repo))
     if run_script is not None: request['runScript']=run_script
     with self.assertRaisesRegex(ValueError,'start with'):host.dispatch('worktree',request)
   self.assertFalse(marker.exists());self.assertEqual(len(self.branches(repo)),1)
if __name__=='__main__':unittest.main()
