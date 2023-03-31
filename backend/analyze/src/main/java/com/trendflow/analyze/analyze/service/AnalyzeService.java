package com.trendflow.analyze.analyze.service;

import com.trendflow.analyze.analyze.dto.request.*;
import com.trendflow.analyze.analyze.dto.response.*;
import com.trendflow.analyze.analyze.dto.vo.CompareInfo;
import com.trendflow.analyze.analyze.dto.vo.CompareInfoVo;
import com.trendflow.analyze.analyze.dto.vo.GrapeQuotientInfo;
import com.trendflow.analyze.analyze.dto.vo.MentionCountInfo;
import com.trendflow.analyze.analyze.entity.Relation;
import com.trendflow.analyze.analyze.entity.Sentiment;
import com.trendflow.analyze.analyze.entity.SentimentCount;
import com.trendflow.analyze.analyze.repository.RelationRepository;
import com.trendflow.analyze.analyze.repository.SentimentRepository;
import com.trendflow.analyze.global.code.CommonCode;
import com.trendflow.analyze.global.code.SocialCacheCode;
import com.trendflow.analyze.global.exception.NotFoundException;
import com.trendflow.analyze.global.redis.Social;
import com.trendflow.analyze.msa.dto.vo.Keyword;
import com.trendflow.analyze.msa.dto.vo.KeywordCount;
import com.trendflow.analyze.msa.service.CommonService;
import com.trendflow.analyze.msa.service.KeywordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyzeService {
    private final RelationRepository relationRepository;
    private final SentimentRepository sentimentRepository;

    private final CommonService commonService;
    private final KeywordService keywordService;

    @Transactional
    public List<FindSocialResponse> findSocial(FindSocialRequest findSocialRequest) {
        String keyword = findSocialRequest.getKeyword();
        LocalDateTime startDate = findSocialRequest.getStartDate();
        LocalDateTime endDate = findSocialRequest.getEndDate();

        List<Social> socialList = getSocialData(keyword, startDate, endDate);

        return FindSocialResponse.toList(socialList);
    }

    public List<FindRelationContentResponse> findRelationContent(FindRelationContentRequest findRelationContentRequest) {
        System.out.println("findRelationContentRequest = " + findRelationContentRequest);
        return null;
    }

    public List<FindYoutubeResponse> findYoutube(FindYoutubeRequest findYoutubeRequest) {
        System.out.println("findYoutubeRequest = " + findYoutubeRequest);
        return null;
    }

    public List<FindYoutubeCommentResponse> findYoutubeComment(FindYoutubeCommentRequest findYoutubeCommentRequest) {
        System.out.println("findYoutubeCommentRequest = " + findYoutubeCommentRequest);
        return null;
    }

    public List<FindCompareKeywordResponse> findCompareKeyword(FindCompareKeywordRequest findCompareKeywordRequest) {

        List<FindCompareKeywordResponse> findCompareKeywordResponseList = new ArrayList<>();

        String keywordA = findCompareKeywordRequest.getKeywordA();
        String keywordB = findCompareKeywordRequest.getKeywordB();

        LocalDateTime startDate = findCompareKeywordRequest.getStartDate();
        LocalDateTime endDate = findCompareKeywordRequest.getEndDate();

        List<Social> socialAList = getSocialData(keywordA, startDate, endDate);
        List<Social> socialBList = getSocialData(keywordB, startDate, endDate);

        return findCompareKeywordResponseList;
    }

    @Transactional
    public List<FindRelationKeywordResponse> findRelationKeyword(Long keywordId) {
        List<Relation> relationList = relationRepository.findTop8ByKeywordIdOrderByCountDesc(keywordId);
        return relationList.stream()
                .map(FindRelationKeywordResponse::fromEntity)
                .collect(Collectors.toList());
    }

    @Transactional
    public List<FindWordCloudKeywordResponse> findWordCloudKeyword(Long keywordId) {
        List<Relation> relationList = relationRepository.findTop200ByKeywordIdOrderByCountDesc(keywordId);
        return relationList.stream()
                .map(FindWordCloudKeywordResponse::fromEntity)
                .collect(Collectors.toList());
    }

    private List<Social> getSocialData(String keyword, LocalDateTime startDate, LocalDateTime endDate) {
        List<Social> socialList = new ArrayList<>();

        // 플랫폼 별 언급량
        List<KeywordCount> keywordCountList = keywordService.getKeywordCount(keyword, startDate.minusDays(1), endDate);

        // 키워드와 일치하는 키워드 객체 응답
        List<Keyword> keywordList = keywordService.getKeyword(keyword, startDate.minusDays(1), endDate);
        // 키워드의 일자별, 소스별 긍정, 중립, 부정 지수
        List<SentimentCount> sentimentList = sentimentRepository.findBySourceIdIn(keywordList.stream()
                .map(Keyword::getSourceId)
                .collect(Collectors.toList()), startDate, endDate);

        // 맵 생성
        Map<LocalDate, MentionCountInfo> keywordCountMap = new HashMap<>();
        for (KeywordCount keywordCount : keywordCountList) {
            LocalDate now = keywordCount.getRegDt();
            String platformCode = keywordCount.getPlatformCode();
            Long count = keywordCount.getCount();

            if (!keywordCountMap.containsKey(now))
                keywordCountMap.put(now, setMentionCountInfo(MentionCountInfo.builder()
                        .daum(0)
                        .naver(0)
                        .twitter(0)
                        .total(0)
                        .build(), platformCode, count));
            else keywordCountMap.put(now, setMentionCountInfo(keywordCountMap.get(now), platformCode, count));
        }

        Map<LocalDate, GrapeQuotientInfo> sentimentCountMap = new HashMap<>();
        for (SentimentCount sentimentCount : sentimentList) {
            LocalDate now = sentimentCount.getRegDt();
            Double score = sentimentCount.getScore();
            Long count = sentimentCount.getCount();

            if (!sentimentCountMap.containsKey(now))
                sentimentCountMap.put(now, setGrapeQuotientInfo(GrapeQuotientInfo.builder()
                        .positive(0)
                        .negative(0)
                        .neutral(0)
                        .build(), score, count));
            else sentimentCountMap.put(now, setGrapeQuotientInfo(sentimentCountMap.get(now), score, count));
        }


        LocalDate now = startDate.toLocalDate();
        LocalDate end = endDate.toLocalDate();

        // 전날 데이터 확인 후 삽입
        if (!keywordCountMap.containsKey(now.minusDays(1))) {
            keywordCountMap.put(now.minusDays(1), MentionCountInfo.builder()
                    .daum(0)
                    .naver(0)
                    .twitter(0)
                    .total(0)
                    .build());
        }
        if (!sentimentCountMap.containsKey(now.minusDays(1))) {
            sentimentCountMap.put(now.minusDays(1), GrapeQuotientInfo.builder()
                    .positive(0)
                    .negative(0)
                    .neutral(0)
                    .build());
        }

        // 일자별 확인
        while (now.isBefore(end) || now.isEqual(end)) {
            MentionCountInfo mentionCountInfo;
            GrapeQuotientInfo grapeQuotientInfo;

            // 언급량 일자별 정리
            if (keywordCountMap.containsKey(now)) {
                MentionCountInfo nowMentionCount = keywordCountMap.get(now);
                Integer daum = nowMentionCount.getDaum();
                Integer naver = nowMentionCount.getNaver();
                Integer twitter = nowMentionCount.getTwitter();
                mentionCountInfo = MentionCountInfo.builder()
                        .daum(daum)
                        .naver(naver)
                        .twitter(twitter)
                        .total(daum + naver + twitter)
                        .build();
            } else {
                mentionCountInfo = MentionCountInfo.builder()
                        .daum(0)
                        .naver(0)
                        .twitter(0)
                        .total(0)
                        .build();
                keywordCountMap.put(now, mentionCountInfo);
            }

            // 긍부정 지수 일자별 정리
            if (sentimentCountMap.containsKey(now)) {
                GrapeQuotientInfo nowGrapeQuotient = sentimentCountMap.get(now);
                grapeQuotientInfo = GrapeQuotientInfo.builder()
                        .positive(nowGrapeQuotient.getPositive())
                        .negative(nowGrapeQuotient.getNegative())
                        .neutral(nowGrapeQuotient.getNeutral())
                        .build();
            } else {
                grapeQuotientInfo = GrapeQuotientInfo.builder()
                        .positive(0)
                        .negative(0)
                        .neutral(0)
                        .build();
                sentimentCountMap.put(now, grapeQuotientInfo);
            }

            // 비교 분석
            LocalDate past = now.minusDays(1);
            CompareInfoVo mention = compareKeywrodCount(keywordCountMap.get(past), keywordCountMap.get(now));
            CompareInfoVo grapeQuotient = compareSentimentCount(sentimentCountMap.get(past), sentimentCountMap.get(now));

            socialList.add(Social.builder()
                    .date(now)
                    .mentionCountInfo(mentionCountInfo)
                    .grapeQuotientInfo(grapeQuotientInfo)
                            .compareInfo(CompareInfo.builder()
                                    .mention(mention)
                                    .grapeQuotient(grapeQuotient)
                                    .build())
                    .build());

            now = now.plusDays(1);
        }
        return socialList;
    }

    private CompareInfoVo compareKeywrodCount(MentionCountInfo past, MentionCountInfo now) {
        String type;
        Integer changed;

        Integer pastTotal = past.getTotal();
        Integer nowTotal = now.getTotal();

        if (nowTotal > pastTotal) {
            type = SocialCacheCode.TYPE_UP.getCode();
            changed = nowTotal - pastTotal;
        } else if (nowTotal == pastTotal) {
            type = SocialCacheCode.TYPE_SAME.getCode();
            changed = 0;
        } else {
            type = SocialCacheCode.TYPE_DOWN.getCode();
            changed = pastTotal - nowTotal;
        }

        return CompareInfoVo.builder()
                .type(type)
                .changed(changed)
                .build();
    }

    private CompareInfoVo compareSentimentCount(GrapeQuotientInfo past, GrapeQuotientInfo now) {
        String type;
        Integer changed;

        Integer pastGrape = past.getPositive() - past.getNegative();
        Integer nowGrape = now.getPositive() - now.getNegative();

        if (nowGrape > pastGrape) {
            type = SocialCacheCode.TYPE_UP.getCode();
            changed = nowGrape - pastGrape;
        } else if (nowGrape == pastGrape) {
            type = SocialCacheCode.TYPE_SAME.getCode();
            changed = 0;
        } else {
            type = SocialCacheCode.TYPE_DOWN.getCode();
            changed = pastGrape - nowGrape;
        }

        return CompareInfoVo.builder()
                .type(type)
                .changed(changed)
                .build();
    }

    private MentionCountInfo setMentionCountInfo(MentionCountInfo mentionCountInfo, String platformCode, Long count) {
        String DAUM = commonService.getLocalCode(CommonCode.DAUM.getName()).getCode();
        String NAVER = commonService.getLocalCode(CommonCode.NAVER.getName()).getCode();
        String TWITTER = commonService.getLocalCode(CommonCode.TWITTER.getName()).getCode();

        if (platformCode.equals(DAUM)) mentionCountInfo.setDaum(count.intValue());
        else if (platformCode.equals(NAVER)) mentionCountInfo.setNaver(count.intValue());
        else if (platformCode.equals(TWITTER)) mentionCountInfo.setTwitter(count.intValue());
        mentionCountInfo.setTotal(mentionCountInfo.getDaum() + mentionCountInfo.getNaver() + mentionCountInfo.getTwitter());

        return mentionCountInfo;
    }

    private GrapeQuotientInfo setGrapeQuotientInfo(GrapeQuotientInfo grapeQuotientInfo, Double score, Long count) {
        if (score > 0.0) grapeQuotientInfo.setPositive(count.intValue());
        else if (score < 0.0) grapeQuotientInfo.setNegative(count.intValue());
        else grapeQuotientInfo.setNeutral(count.intValue());

        return grapeQuotientInfo;
    }
}