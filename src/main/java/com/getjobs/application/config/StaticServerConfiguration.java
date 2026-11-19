package com.getjobs.application.config;

import org.apache.catalina.connector.Connector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 静态资源服务器配置
 * 当前端 dev 服务未运行时，在 6866 端口提供静态资源
 */
@Slf4j
@Configuration
public class StaticServerConfiguration {
    private static final int FRONTEND_PORT = 6866;
    private static final String DIST_PATH = "src/main/resources/dist";
    private static final String STATIC_PATH = "src/main/resources/static";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> servletContainer() {
        return server -> {
            // 检查前端 dev 服务是否运行
            boolean hasFrontendDev = detectFrontendDevServer();

            if (hasFrontendDev) {
                log.info("检测到前端开发服务运行在端口 {}", FRONTEND_PORT);
                return;
            }

            // 检查是否有静态资源（文件系统或 classpath）
            boolean hasStaticResources = checkStaticResources();

            if (hasStaticResources) {
                log.info("未检测到前端开发服务，但找到静态资源");
                log.info("配置额外端口 {} 用于提供静态资源", FRONTEND_PORT);

                Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
                connector.setPort(FRONTEND_PORT);
                server.addAdditionalTomcatConnectors(connector);
            } else {
                log.warn("未检测到前端开发服务，也未找到静态资源");
            }
        };
    }

    /**
     * 探测前端开发服务器是否在运行
     * 尝试 IPv4 和 IPv6
     */
    private boolean detectFrontendDevServer() {
        // 尝试多个地址：IPv4 和 IPv6
        String[] hosts = {"127.0.0.1", "[::1]", "localhost"};

        for (String host : hosts) {
            try {
                String bareHost = host;
                if (bareHost.startsWith("[") && bareHost.endsWith("]")) {
                    bareHost = bareHost.substring(1, bareHost.length() - 1);
                }
                URI uri = new URI("http", null, bareHost, FRONTEND_PORT, "/", null, null);
                URL url = uri.toURL();
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setInstanceFollowRedirects(false);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                if (responseCode >= 200 && responseCode < 500) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("检测前端服务失败 ({}): {}", host, e.getMessage());
            }
        }

        log.debug("前端开发服务检测失败，已尝试所有地址");
        return false;
    }

    /**
     * 检查静态资源是否存在（文件系统或 classpath）
     */
    private boolean checkStaticResources() {
        Path distPath = Paths.get(DIST_PATH);
        Path staticPath = Paths.get(STATIC_PATH);

        log.info("检查静态资源路径:");
        log.info("  dist路径: {} (绝对路径: {})", DIST_PATH, distPath.toAbsolutePath());
        log.info("  static路径: {} (绝对路径: {})", STATIC_PATH, staticPath.toAbsolutePath());

        boolean hasDist = hasContent(distPath);
        boolean hasStatic = hasContent(staticPath);

        // 也检查 classpath 中的资源
        boolean hasClasspathDist = hasClasspathContent("dist/");
        boolean hasClasspathStatic = hasClasspathContent("static/");

        log.info("  dist存在: {}", hasDist);
        log.info("  static存在: {}", hasStatic);
        log.info("  classpath:/dist/ 存在: {}", hasClasspathDist);
        log.info("  classpath:/static/ 存在: {}", hasClasspathStatic);

        return hasDist || hasStatic || hasClasspathDist || hasClasspathStatic;
    }

    /**
     * 检查目录是否有内容
     */
    private boolean hasContent(Path path) {
        try {
            if (!Files.exists(path)) {
                return false;
            }
            return Files.list(path).findAny().isPresent();
        } catch (IOException e) {
            log.error("检查路径失败: {}", path, e);
            return false;
        }
    }

    /**
     * 检查 classpath 资源是否存在且有内容
     */
    private boolean hasClasspathContent(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return false;
            }
            // 尝试列出目录内容
            if (resource.getURL().getProtocol().equals("jar")) {
                // JAR 内的资源，简单检查是否存在
                return true;
            }
            // 文件系统资源，检查目录是否有内容
            File dir = resource.getFile();
            if (dir.isDirectory()) {
                String[] children = dir.list();
                return children != null && children.length > 0;
            }
            return true;
        } catch (Exception e) {
            log.debug("检查 classpath 资源失败: {}", path, e.getMessage());
            return false;
        }
    }
}
