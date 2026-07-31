package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.entity.AiTraceSpan;
import com.hmdp.mapper.AiTraceSpanMapper;
import com.hmdp.service.IAiTraceSpanService;
import org.springframework.stereotype.Service;

@Service
public class AiTraceSpanServiceImpl extends ServiceImpl<AiTraceSpanMapper, AiTraceSpan>
        implements IAiTraceSpanService {
}
