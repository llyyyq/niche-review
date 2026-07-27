package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.AiToolLog;
import com.hmdp.mapper.AiToolLogMapper;
import com.hmdp.service.IAiToolLogService;
import org.springframework.stereotype.Service;

@Service
public class AiToolLogServiceImpl extends ServiceImpl<AiToolLogMapper, AiToolLog>
        implements IAiToolLogService {
}
