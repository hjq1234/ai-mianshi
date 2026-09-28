package com.ke.nhservice.aimianshi.biz.knowledge;

import java.util.List;

/**
 * 知识库检索。一期只提供接口不实现，
 * 二期接国产 embedding API 时新增实现类即可，调用方不用改。
 */
public interface Retriever {

    List<Chunk> retrieve(String query, int topK);
}