package com.ke.nhservice.aimianshi.common.dto;

import java.util.Map;

/**
 * 复盘页雷达图的历史对比数据：该用户所有**已完成**场次的五维均分。
 *
 * 为什么单独一个接口而不是塞进 /detail：/detail 是「这一场」的事实，
 * 历史均分是「所有场」的聚合，两者的查询成本和缓存性格都不同。
 * 而且拿不到历史均分时雷达图只该降级成一条多边形，不该连累整页。
 *
 * @param finishedCount 参与统计的场次（只算答过题的已完成场次）。0 表示没有历史，
 *                      前端只画本场那条多边形。图爆掉但一题没答的场次不算——
 *                      否则会出现「历史均分（3 场）」而实际只有 2 场的数据
 * @param dimensions    五维均分。practice 这类可空维度只统计「真的打过分」的题，
 *                      不会被 null 当成 0 拉低
 * @param totalScore    总分均分，同上
 */
public record InterviewStatsVO(
        int finishedCount,
        Map<String, Double> dimensions,
        Double totalScore) {
}