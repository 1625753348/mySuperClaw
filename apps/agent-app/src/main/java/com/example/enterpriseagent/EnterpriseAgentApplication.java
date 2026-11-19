package com.example.enterpriseagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业智能助手的启动入口。API 和 Worker 共用同一个构建产物，
 * 由 Spring Profile 选择进程形态。
 */
@SpringBootApplication
public class EnterpriseAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(EnterpriseAgentApplication.class, args);
    }
}
