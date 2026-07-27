package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.AiRequestLog;
import com.hmdp.mapper.AiRequestLogMapper;
import com.hmdp.service.IAiRequestLogService;
import org.springframework.stereotype.Service;

@Service
public class AiRequestLogServiceImpl extends ServiceImpl<AiRequestLogMapper, AiRequestLog>
        implements IAiRequestLogService {
}
