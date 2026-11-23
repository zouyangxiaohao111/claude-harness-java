/**
 * 连接器图标（本地打包资源）· 30 个品牌图标拷自 Octop（MIT，商标归各所有者，仅用于识别服务）。
 *
 * <p>Vite `import` 编译成**同源 hash 资源**：离线可用、无 CORS、不产生热链/403。
 * ⛔ 不要把这里的地址换回远程 URL，也不要给 `<img>` 加 onError 去抓 favicon。
 *
 * <p>品牌图标版权归各自所有者；仅用于表示「连接到该服务」，不暗示官方合作/背书。
 */
import tencentArdot from './tencent-ardot.png'
import baiduMap from './baidu-map.png'
import ctripWendao from './ctrip-wendao.png'
import didi from './didi.svg'
import dingtalkCli from './dingtalk-cli.png'
import dida365 from './dida365.png'
import dify from './dify.svg'
import agentlyCli from './agently-cli.png'
import feishuCli from './feishu-cli.png'
import fliggy from './fliggy.png'
import meituanTravel from './meituan-travel.png'
import notion from './notion.png'
import obsidianCli from './obsidian-cli.svg'
import openalex from './openalex.svg'
import qcc from './qcc.png'
import ragflow from './ragflow.svg'
import qqMail from './qq-mail.png'
import qqMusic from './qq-music.png'
import tencentDocs from './tencent-docs.png'
import tencentIma from './tencent-ima.png'
import tencentMeeting from './tencent-meeting.png'
import tencentNews from './tencent-news.png'
import tencentLexiang from './tencent-lexiang.png'
import tencentWeiyun from './tencent-weiyun.png'
import wechatReading from './wechat-reading.png'
import wecomCli from './wecom-cli.png'
import weknora from './weknora.svg'
import youdaoNote from './youdao-note.png'
import yuandian from './yuandian.png'
import yuqueMcp from './yuque-mcp.png'

const CONNECTOR_LOGOS: Record<string, string> = {
  'tencent-ardot': tencentArdot,
  'baidu-map': baiduMap,
  'ctrip-wendao': ctripWendao,
  'didi': didi,
  'dingtalk-cli': dingtalkCli,
  'dida365': dida365,
  'dify': dify,
  'agently-cli': agentlyCli,
  'feishu-cli': feishuCli,
  'fliggy': fliggy,
  'meituan-travel': meituanTravel,
  'notion': notion,
  'obsidian-cli': obsidianCli,
  'openalex': openalex,
  'qcc': qcc,
  'ragflow': ragflow,
  'qq-mail': qqMail,
  'qq-music': qqMusic,
  'tencent-docs': tencentDocs,
  'tencent-ima': tencentIma,
  'tencent-meeting': tencentMeeting,
  'tencent-news': tencentNews,
  'tencent-lexiang': tencentLexiang,
  'tencent-weiyun': tencentWeiyun,
  'wechat-reading': wechatReading,
  'wecom-cli': wecomCli,
  'weknora': weknora,
  'youdao-note': youdaoNote,
  'yuandian': yuandian,
  'yuque-mcp': yuqueMcp,
}

/** kind（或 catalog 的 icon 标识）→ 本地图标地址；取不到返回 undefined（调用方退回首字母色块） */
export function connectorLogo(kind?: string | null, icon?: string | null): string | undefined {
  for (const k of [kind, icon]) {
    if (!k) continue
    const direct = CONNECTOR_LOGOS[k]
    if (direct) return direct
    const normalized = k.toLowerCase().replace(/[_\s]+/g, '-')
    if (normalized !== k && CONNECTOR_LOGOS[normalized]) return CONNECTOR_LOGOS[normalized]
  }
  return undefined
}
