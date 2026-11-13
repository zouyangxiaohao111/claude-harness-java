/**
 * nexus 品牌标：启动体验那只蓝猫的剪影（`CatArt` 是单一素材源，CatLoader / welcome 同用）。
 *
 * <p>身体用 CatArtBody 原样 path，尾巴用 CatTail 叠加在右后方（启动画面里也是这么叠的：
 * 尾巴先画、身体压住连接处）。20×20 的容器里靠 globals.css `.nx-mark` 的绝对定位摆放。
 */
import { CatArtBody, CatTail } from '../startup/CatArt'

export function NexusMark() {
  return (
    <span className="nx-mark" aria-hidden="true">
      <CatTail className="nx-tail" />
      <CatArtBody className="nx-body" />
    </span>
  )
}
