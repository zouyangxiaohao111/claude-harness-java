package com.nexusai.wechatlog.key.elevation;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 给提权交接用的临时文件/目录收紧权限。
 *
 * <p>交接文件里是数据库密钥，必须做到「只有当前用户能读写」。抽成接口是为了
 * 在不改 ACL 的前提下单测「读后即删」等流程。
 */
@FunctionalInterface
public interface AclRestrictor {

    /**
     * 收紧权限。
     *
     * @param path      目标
     * @param directory true=目录（需要给子项继承），false=文件
     * @throws IOException 无法收紧时必须抛出 —— 调用方会放弃本次提权，
     *                     绝不允许在权限未收紧的情况下继续交接密钥
     */
    void restrict(Path path, boolean directory) throws IOException;
}
