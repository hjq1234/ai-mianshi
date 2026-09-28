package com.ke.nhservice.aimianshi.biz.knowledge;

import org.springframework.stereotype.Component;

import java.util.List;

/** 一期默认实现：永远返回空。二期换掉这个类，其余代码无感 */
@Component
public class EmptyRetriever implements Retriever {

    @Override
    public List<Chunk> retrieve(String query, int topK) {
        return List.of();
    }
}