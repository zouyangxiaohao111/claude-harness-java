package com.nexusai.wechatlog.key.elevation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 默认实现：Windows 上把 ACL 收紧成「只有文件/目录属主（当前用户）」，去掉继承；
 * POSIX 上设置 700/600。
 *
 * <h2>为什么属主写权限对提权子进程仍然有效</h2>
 * UAC 提权是同一用户账户的<b>提权令牌</b>（多出 Administrators 组），用户 SID 不变。
 * 因此只给属主（用户 SID）授权时，提权子进程仍可写。
 *
 * <p>若文件系统不支持 ACL（FAT/网络盘）则抛 {@link IOException}，调用方放弃提权并降级
 * —— 宁可让用户走「以管理员身份重启」，也不把密钥写进一个别人可能读到的位置。
 */
public final class OwnerOnlyAclRestrictor implements AclRestrictor {

    @Override
    public void restrict(Path path, boolean directory) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            try {
                Files.setPosixFilePermissions(path,
                        PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 且非 Windows：没有可用的收紧手段，按「无法安全交接」处理
                throw new IOException("无法收紧临时文件权限: " + path);
            }
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("文件系统不支持 ACL: " + path);
        }
        UserPrincipal owner = Files.getOwner(path);
        Set<AclEntryPermission> perms = EnumSet.allOf(AclEntryPermission.class);
        AclEntry.Builder builder = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(perms);
        if (directory) {
            builder.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        }
        view.setAcl(List.of(builder.build()));
    }
}
