using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Diagnostics;
using System.Threading.Tasks;
using System.Collections.Generic;
using System.Windows.Forms;
using System.Drawing;
using System.Management;

namespace PopTagUpdate {
 public class ReleaseAsset { public string name; public string browser_download_url; public long size; }
 public class Release { public bool draft; public bool prerelease; public string tag_name; public List<ReleaseAsset> assets; }
 public class Launcher : Form {
  const string Repo="Fish0731/PopTag";
  readonly Engine engine;
  readonly TextBox log=new TextBox{Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Vertical,Dock=DockStyle.Fill};
  readonly Label status=new Label{Text="准备就绪",AutoSize=true};
  readonly Button check=new Button{Text="检查更新",Width=110};
  readonly Button start=new Button{Text="更新并启动",Width=130};
  readonly Button offline=new Button{Text="离线启动",Width=110};
  readonly Button stop=new Button{Text="关闭本地游戏",Width=120};
  readonly Button rollback=new Button{Text="回滚上次更新",Width=130};
  bool busy;bool diagnosticServerOnly;Manifest available;Release release;
  Action<string> report;
  public Launcher(string root) {
   engine=new Engine(root);Text="PopTag 本地版 · 启动与更新";Size=new Size(790,450);MinimumSize=new Size(710,380);StartPosition=FormStartPosition.CenterScreen;Font=new Font("Microsoft YaHei UI",10);
   var bar=new FlowLayoutPanel{Dock=DockStyle.Top,Height=52,Padding=new Padding(10)};bar.Controls.AddRange(new Control[]{check,start,offline,stop,rollback});
   var foot=new Panel{Dock=DockStyle.Bottom,Height=35,Padding=new Padding(10,5,0,0)};foot.Controls.Add(status);Controls.Add(log);Controls.Add(bar);Controls.Add(foot);
   report=s=>{if(IsDisposed)return;if(InvokeRequired){BeginInvoke(new Action(()=>WriteLog(s)));}else WriteLog(s);};
   check.Click+=async(s,e)=>await Run(Check);
   start.Click+=async(s,e)=>await Run(async()=>{await Check();await InstallUpdate();await Launch();});
   offline.Click+=async(s,e)=>await Run(Launch);
   stop.Click+=async(s,e)=>await Run(async()=>{await Task.Run((Action)StopOwned);report("本目录的游戏和服务端已关闭。");});
   rollback.Click+=async(s,e)=>await Run(async()=>{EnsureStopped();await Task.Run((Action)engine.Rollback);report("已回滚，账号存档保持原样。");});
   FormClosing+=(s,e)=>{if(busy){e.Cancel=true;report("正在处理文件，请等待操作完成后再关闭。");}};
   Shown+=async(s,e)=>{
    report("安装目录："+engine.Root);report("更新源：https://github.com/"+Repo+"/releases");
    if(engine.Pending){report("检测到上次更新中断。请先点击回滚，再启动。");return;}
    await Run(Check);
   };
  }
  void WriteLog(string text){log.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+text+Environment.NewLine);status.Text=text.Length>75?text.Substring(0,75)+"…":text;}
  async Task Run(Func<Task> action){if(busy)return;busy=true;foreach(var b in new[]{check,start,offline,stop,rollback})b.Enabled=false;
   try {await action();}catch(Exception ex){report("未完成："+ex.Message);report(ex is HttpRequestException || ex is WebException ? "网络检查失败，可使用离线启动；恢复网络后再更新。" : "请查看 logs/server.err.log；若有中断事务请先回滚。账号存档保持不变。");}
   finally{busy=false;foreach(var b in new[]{check,start,offline,stop,rollback})b.Enabled=true;}}
  static HttpClient Client(){ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;var c=new HttpClient();c.Timeout=TimeSpan.FromMinutes(20);c.DefaultRequestHeaders.UserAgent.ParseAdd("PopTagUpdater/1.0");c.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");return c;}
  static void ValidateUrl(string url){Uri u;if(!Uri.TryCreate(url,UriKind.Absolute,out u)||u.Scheme!="https"||u.Host!="github.com"||!u.AbsolutePath.StartsWith("/"+Repo+"/releases/download/",StringComparison.OrdinalIgnoreCase)||u.UserInfo!="")throw new InvalidDataException("附件不是指定仓库的HTTPS发布文件");}
  async Task<byte[]> ReadSmall(HttpClient c,string url,int limit,bool allowNotFound=false){using(var response=await c.GetAsync(url,HttpCompletionOption.ResponseHeadersRead)){if(allowNotFound&&response.StatusCode==HttpStatusCode.NotFound)return null;response.EnsureSuccessStatusCode();using(var input=await response.Content.ReadAsStreamAsync())using(var output=new MemoryStream()){var buf=new byte[8192];int n;while((n=await input.ReadAsync(buf,0,buf.Length))>0){if(output.Length+n>limit)throw new InvalidDataException("版本信息过大");output.Write(buf,0,n);}return output.ToArray();}}}
  async Task Check(){
   available=null;report("正在检查 GitHub 正式发布版本…");
   using(var c=Client()) {
    var body=await ReadSmall(c,"https://api.github.com/repos/"+Repo+"/releases/latest",4000000,true);
    if(body==null){report("仓库尚未发布正式更新，可离线启动已有完整版本。");return;}
    release=Engine.Json.Deserialize<Release>(System.Text.Encoding.UTF8.GetString(body));
    if(release.draft||release.prerelease||release.assets==null)throw new InvalidDataException("没有可用正式版本");
    var a=release.assets.SingleOrDefault(x=>x.name=="update-manifest.json");
    if(a==null){report("此发布没有自动更新清单，请先安装完整包。");return;}
    ValidateUrl(a.browser_download_url);
    var data=await ReadSmall(c,a.browser_download_url,8000000);
    var m=Engine.Json.Deserialize<Manifest>(System.Text.Encoding.UTF8.GetString(data));Engine.ValidateGameRelease(m);
    if(m.version!=release.tag_name)throw new InvalidDataException("版本清单与发布标签不一致");
    var current=Path.Combine(engine.State,"current.json");
    if(File.Exists(current)){
     var installed=Engine.Json.Deserialize<Manifest>(File.ReadAllText(current));
     Version localVersion, remoteVersion;
     if(Version.TryParse(installed.version.TrimStart('v').Split('-')[0],out localVersion)
        && Version.TryParse(m.version.TrimStart('v').Split('-')[0],out remoteVersion)
        && localVersion>remoteVersion){
      report("本机版本 "+installed.version+" 比公开版更新，保留本机版本。");return;
     }
    }
    foreach(var b in m.bundles){var asset=release.assets.SingleOrDefault(x=>x.name==b.asset);if(asset==null||asset.size!=b.size)throw new InvalidDataException("发布附件不完整");ValidateUrl(asset.browser_download_url);}
    var needed=await Task.Run(()=>engine.Needed(m));available=m;
    report(needed.Count==0?"文件已是最新版本："+m.version:"发现版本 "+m.version+"；需下载 "+needed.Count+" 个组件，共 "+(needed.Sum(x=>x.size)/1024.0/1024).ToString("F1")+" MiB。");
    if(!String.IsNullOrWhiteSpace(m.notes))report(m.notes);
   }
  }
  async Task InstallUpdate(){
   if(available==null)return;
   EnsureStopped();if(engine.Pending)throw new IOException("上次更新中断，请先回滚");
   var needed=await Task.Run(()=>engine.Needed(available));
   var desired=available.bundles.SelectMany(b=>b.files).Select(f=>f.path).ToHashSetCompat();
   var current=Path.Combine(engine.State,"current.json");
   bool removal=File.Exists(current)&&Engine.Json.Deserialize<Manifest>(File.ReadAllText(current)).bundles.SelectMany(b=>b.files).Any(f=>!desired.Contains(f.path));
   if(needed.Count==0&&!removal&&File.Exists(current)&&File.ReadAllText(current)==Engine.Json.Serialize(available))return;
   var work=Path.Combine(engine.State,"downloads",Guid.NewGuid().ToString("N"));Engine.RejectLinks(work);Directory.CreateDirectory(work);
   using(var c=Client())foreach(var b in needed){
    var asset=release.assets.Single(x=>x.name==b.asset);ValidateUrl(asset.browser_download_url);var zip=Path.Combine(work,b.asset);report("下载："+b.asset);
    using(var response=await c.GetAsync(asset.browser_download_url,HttpCompletionOption.ResponseHeadersRead)){response.EnsureSuccessStatusCode();using(var input=await response.Content.ReadAsStreamAsync())using(var output=File.Create(zip)){var buf=new byte[81920];long nbytes=0;int n;var clock=Stopwatch.StartNew();while((n=await input.ReadAsync(buf,0,buf.Length))>0){nbytes+=n;if(nbytes>b.size)throw new InvalidDataException("下载超过声明长度");await output.WriteAsync(buf,0,n);if(clock.ElapsedMilliseconds>1500){report("下载 "+b.asset+"："+(100.0*nbytes/b.size).ToString("F0")+"%");clock.Restart();}}}}
    await Task.Run(()=>engine.Stage(b,zip,Path.Combine(work,"stage")));
   }
   EnsureStopped();report("校验完成，正在备份并替换文件…");
   await Task.Run(()=>{engine.ArchiveTransaction();engine.Apply(available,Path.Combine(work,"stage"));});
   report("更新完成："+available.version+"。上一个版本的文件已备份，可回滚。");
  }
  IEnumerable<Process> OwnedGames(){foreach(var p in Process.GetProcessesByName("CA")){bool own=false;try{own=String.Equals(p.MainModule.FileName,Path.Combine(engine.Root,"client/CA.exe"),StringComparison.OrdinalIgnoreCase);}catch{}if(own)yield return p;}}
  bool OwnServer(out Process process){process=null;var pid=Path.Combine(engine.Root,"logs/server.pid");int id;if(!File.Exists(pid)||!Int32.TryParse(File.ReadAllText(pid).Trim(),out id))return false;
   try {using(var search=new ManagementObjectSearcher("SELECT ExecutablePath,CommandLine FROM Win32_Process WHERE ProcessId="+id))foreach(ManagementObject p in search.Get()){
    var exe=Convert.ToString(p["ExecutablePath"]);var cmd=Convert.ToString(p["CommandLine"]);
    if(String.Equals(exe,Path.Combine(engine.Root,"runtime/bin/java.exe").Replace('/','\\'),StringComparison.OrdinalIgnoreCase)&&cmd.IndexOf(Path.Combine(engine.Root,"server/classes").Replace('/','\\'),StringComparison.OrdinalIgnoreCase)>=0){process=Process.GetProcessById(id);return true;}
   }}catch{}return false;
  }
  void EnsureStopped(){
   Process p;bool runtimeBusy=false;
   foreach(var process in Process.GetProcessesByName("java").Concat(Process.GetProcessesByName("javaw")))try {
    if(process.MainModule.FileName.StartsWith(Path.Combine(engine.Root,"runtime")+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase))runtimeBusy=true;
   }catch{}
   if(OwnedGames().Any()||runtimeBusy||OwnServer(out p))throw new IOException("请先点击“关闭本地游戏”，再更新或回滚");
  }
  void StopOwned(){foreach(var p in OwnedGames()){p.CloseMainWindow();if(!p.WaitForExit(5000))throw new IOException("游戏未退出，请手动关闭；不会强制终止对局");}Process server;if(OwnServer(out server)){server.Kill();server.WaitForExit(5000);}}
  async Task Launch(){
   if(engine.Pending)throw new IOException("更新未完成，请先回滚");
   if(OwnedGames().Any()){report("本地游戏已经打开。");return;}
   var java=Path.Combine(engine.Root,"runtime/bin/java.exe");var game=Path.Combine(engine.Root,"client/CA.exe");var classes=Path.Combine(engine.Root,"server/classes").Replace('/','\\');
   if(!File.Exists(java)||!File.Exists(game)||!File.Exists(Path.Combine(classes,"bnbemu/MainKt.class")))throw new IOException("请将启动器放在完整游戏包根目录；当前目录不是完整安装");
   foreach(var cfg in new[]{"Config.yaml","EN_18_recv.yaml","EN_18_send.yaml"})
    if(!File.Exists(Path.Combine(engine.Root,"server",cfg)))throw new IOException("服务端配置缺失："+cfg+"；请先更新或重新安装完整包。");
   Process server;
   if(!OwnServer(out server)){
    if(System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Any(p=>p.Port==3838||p.Port==47611))throw new IOException("其他本地服务占用了游戏端口，请先关闭其他版本");
    server=new Process { StartInfo=new ProcessStartInfo(java,"-cp \""+classes+";"+Path.Combine(engine.Root,"server/lib/*")+"\" bnbemu.MainKt"){WorkingDirectory=Path.Combine(engine.Root,"server"),UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true} };
    var output=Path.Combine(engine.Root,"logs");Directory.CreateDirectory(output);
    server.OutputDataReceived+=(sender,e)=>{if(e.Data!=null)try{File.AppendAllText(Path.Combine(output,"server.out.log"),e.Data+Environment.NewLine);}catch{}};
    server.ErrorDataReceived+=(sender,e)=>{if(e.Data!=null)try{File.AppendAllText(Path.Combine(output,"server.err.log"),e.Data+Environment.NewLine);}catch{}};
    server.Start();server.BeginOutputReadLine();server.BeginErrorReadLine();
    var logs=Path.Combine(engine.Root,"logs");Directory.CreateDirectory(logs);File.WriteAllText(Path.Combine(logs,"server.pid"),server.Id.ToString());
   }
   for(int i=0;i<60;i++){
    if(server.HasExited)throw new IOException("本地服务端启动失败");
    var ports=System.Net.NetworkInformation.IPGlobalProperties.GetIPGlobalProperties().GetActiveTcpListeners().Select(p=>p.Port);
    if(ports.Contains(3838)&&ports.Contains(47611)){if(!diagnosticServerOnly)Process.Start(new ProcessStartInfo(game){WorkingDirectory=Path.Combine(engine.Root,"client")});report(diagnosticServerOnly?"本地服务端就绪，3838/47611均已监听。":"游戏已启动。");return;}
    await Task.Delay(500);
   }
   throw new IOException("服务端准备超时，请关闭本地游戏后重试");
  }
  [STAThread]public static void Main(string[] args){
   Application.EnableVisualStyles();Application.SetCompatibleTextRenderingDefault(false);
   if(args.Length==1&&args[0]=="--self-test") {
    using(var form=new Launcher(AppDomain.CurrentDomain.BaseDirectory)) {
     var buttons=form.Controls.OfType<FlowLayoutPanel>().Single().Controls.OfType<Button>().ToList();
     if(buttons.Count!=5||!buttons.Any(b=>b.Text=="离线启动")||!buttons.Any(b=>b.Text=="回滚上次更新"))Environment.Exit(1);
    }
    return;
   }
   if(args.Length==2&&args[0]=="--server-test") {
    try {using(var form=new Launcher(AppDomain.CurrentDomain.BaseDirectory)) {
     var messages=new List<string>();form.report=s=>messages.Add(s);form.diagnosticServerOnly=true;
     Task.Run(async()=>await form.Launch()).GetAwaiter().GetResult();
     File.WriteAllLines(args[1],messages);
    }}catch(Exception ex){File.WriteAllText(args[1],ex.ToString());Environment.Exit(1);}return;
   }
   if(args.Length==2&&args[0]=="--check-only") {
    try {using(var form=new Launcher(AppDomain.CurrentDomain.BaseDirectory)) {
     var messages=new List<string>();form.report=s=>messages.Add(s);
     Task.Run(async()=>await form.Check()).GetAwaiter().GetResult();
     File.WriteAllLines(args[1],messages);
     if(form.available==null)Environment.Exit(2);
    }}catch(Exception ex){File.WriteAllText(args[1],ex.ToString());Environment.Exit(1);}return;
   }
   var key=BitConverter.ToString(System.Security.Cryptography.SHA256.Create().ComputeHash(System.Text.Encoding.UTF8.GetBytes(AppDomain.CurrentDomain.BaseDirectory.ToUpperInvariant()))).Replace("-","");
   bool created;
   using(var mutex=new System.Threading.Mutex(true,"Local\\PopTagUpdater-"+key,out created)){
    if(!created){MessageBox.Show("这个目录的启动器已经打开。","PopTag");return;}
    try{Application.Run(new Launcher(AppDomain.CurrentDomain.BaseDirectory));}finally{mutex.ReleaseMutex();}
   }
  }
 }
 static class Compat {public static HashSet<string> ToHashSetCompat(this IEnumerable<string> s){return new HashSet<string>(s,StringComparer.OrdinalIgnoreCase);}}
}
