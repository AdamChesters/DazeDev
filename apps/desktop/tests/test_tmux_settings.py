from unittest.mock import patch
import unittest
import test_host
host = test_host.host

class TmuxSettingsTests(unittest.TestCase):
 setUp = test_host.HostTests.setUp
 tearDown = test_host.HostTests.tearDown
 def start(self):
  host.tmux('-f','/dev/null','new-session','-d','-s','settings','sleep 60')
  return host.sessions()[0]
 def test_default_and_session_scope_inheritance_and_reset(self):
  session=self.start()
  defaults=host.tmux_settings_read({})
  self.assertEqual(defaults['socket'],host.SOCKET)
  self.assertTrue(defaults['version'].startswith('tmux'))
  changed=host.tmux_settings_apply({'revision':defaults['revision'],'name':'mouse','value':'on'})
  args={'session':session['id'],'stamp':session['stamp']}
  current=host.tmux_settings_read(args)
  mouse=next(o for o in current['options'] if o['name']=='mouse')
  self.assertEqual(mouse['value'],'on');self.assertTrue(mouse['inherited'])
  changed=host.tmux_settings_apply(dict(args,revision=current['revision'],name='mouse',value='off'))
  self.assertEqual(next(o for o in changed['options'] if o['name']=='mouse')['value'],'off')
  restored=host.tmux_settings_apply(dict(args,revision=changed['revision'],name='mouse',reset=True))
  self.assertTrue(next(o for o in restored['options'] if o['name']=='mouse')['inherited'])
 def test_window_scope_and_other_session_unchanged(self):
  session=self.start();host.tmux('new-session','-d','-s','other','sleep 60')
  other=next(s for s in host.sessions() if s['id']!=session['id'])
  args={'session':session['id'],'stamp':session['stamp']}
  current=host.tmux_settings_read(args)
  changed=host.tmux_settings_apply(dict(args,window=current['window'],revision=current['revision'],name='mode-keys',value='vi'))
  self.assertEqual(next(o for o in changed['options'] if o['name']=='mode-keys')['value'],'vi')
  untouched=host.tmux_settings_read({'session':other['id'],'stamp':other['stamp']})
  self.assertTrue(next(o for o in untouched['options'] if o['name']=='mode-keys')['inherited'])
 def test_reject_stale_session_window_and_value_injection(self):
  session=self.start();current=host.tmux_settings_read({})
  for name,value in [('mouse','on; kill-server'),('history-limit','-1'),('history-limit','100001'),('status-left','#(touch /tmp/unwanted)'),('status','on\noff')]:
   with self.assertRaises(ValueError):host.tmux_settings_apply({'revision':current['revision'],'name':name,'value':value})
  host.tmux('set-option','-g','mouse','on')
  with self.assertRaisesRegex(RuntimeError,'Settings changed'):host.tmux_settings_apply({'revision':current['revision'],'name':'mouse','value':'off'})
  with self.assertRaisesRegex(RuntimeError,'Session changed'):host.tmux_settings_read({'session':session['id'],'stamp':'stale'})
  with self.assertRaisesRegex(RuntimeError,'Window changed'):host.tmux_settings_read({'session':session['id'],'stamp':session['stamp'],'window':'@9999'})
 def test_read_does_not_start_server(self):
  current=host.tmux_settings_read({});self.assertEqual(current['sessions'],[]);self.assertEqual(current['options'],[])
  self.assertEqual(host.sessions(),[])
 def test_missing_tmux_and_unsupported_option(self):
  with patch.object(host.shutil,'which',return_value=None):
   with self.assertRaisesRegex(RuntimeError,'not installed'):host.tmux_settings_read({})
  self.start();original=host.tmux
  def run(*args,**kwargs):
   if args[-1]=='window-size':raise RuntimeError('invalid option')
   return original(*args,**kwargs)
  with patch.object(host,'tmux',side_effect=run):
   current=host.tmux_settings_read({})
   self.assertTrue(next(o for o in current['options'] if o['name']=='window-size')['unsupported'])

 def test_window_selection_validates_membership_and_stamp(self):
  session=self.start();other=host.tmux('new-session','-d','-P','-F','#{session_id}','-s','other','sleep 60')
  added=host.tmux('new-window','-d','-P','-F','#{window_id}','-t',session['id'],'sleep 60')
  args={'session':session['id'],'stamp':session['stamp']}
  result=host.tmux_window_select(dict(args,window=added))
  self.assertTrue(next(w for w in result['windows'] if w['id']==added)['active'])
  foreign=host.tmux('list-windows','-t',other,'-F','#{window_id}').splitlines()[0]
  with self.assertRaisesRegex(RuntimeError,'Window changed'):host.tmux_window_select(dict(args,window=foreign))
  with self.assertRaisesRegex(RuntimeError,'Session changed'):host.tmux_window_select(dict(args,stamp='stale',window=added))
  with self.assertRaises(ValueError):host.tmux_window_select({'window':added})
