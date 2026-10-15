package com.hiki.isup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 海康 ISUP 5.0 接入服务启动类。
 *
 * TODO 出库录像同步稳定性优化（保持现有业务规则）：
 * 1. 补齐两个录像 URL 已写入、最终状态未更新的恢复逻辑，避免回收为 PENDING 后
 *    被待处理查询排除；复用已有录像，仅完成状态收尾，不重复下载。
 * 2. 显式配置 OSS 连接、读取及整体请求超时，与本轮处理时限协调；
 *    超时值需考虑录像大小，避免误伤正常上传，验证取消后上传线程的退出行为。
 * 3. 本地录像删除失败后保留待清理目录，定期有限重试；
 *    严格限定为已结束任务的目录，避免误删正在下载或上传的文件。
 */
@SpringBootApplication
@EnableScheduling
public class IsupServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(IsupServerApplication.class, args);
    }
}
