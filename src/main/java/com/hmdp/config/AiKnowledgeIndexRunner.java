package com.hmdp.config;

import com.hmdp.service.IShopKnowledgeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
@Order(100)
public class AiKnowledgeIndexRunner implements ApplicationRunner {

    @Resource
    private AiKnowledgeProperties knowledgeProperties;

    @Resource
    private IShopKnowledgeService shopKnowledgeService;

    @Override
    public void run(ApplicationArguments args) {
        if (!Boolean.TRUE.equals(knowledgeProperties.getRebuildOnStart())) {
            return;
        }
        int count = shopKnowledgeService.rebuildShopKnowledge();
        log.info("AI shop knowledge index rebuilt on startup, shopCount={}", count);
    }
}
