
package com.enjoyiot.server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 项目的启动类
 */
@SuppressWarnings("SpringComponentScan") // 忽略 IDEA 无法识别 ${platform.info.base-package}
@SpringBootApplication(scanBasePackages = {"${platform.info.base-package}.server", "${platform.info.base-package}.module", "${platform.info.base-package}.eiot"})
public class ServerApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(ServerApplication.class, args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
