package com.trendflow.member.auth.service;

import com.trendflow.member.auth.dto.authentication.*;
import com.trendflow.member.auth.dto.response.LoginResponse;
import com.trendflow.member.auth.dto.response.RefreshTokenResponse;
import com.trendflow.member.global.code.AuthCode;
import com.trendflow.member.global.code.CommonCode;
import com.trendflow.member.global.exception.UnAuthException;
import com.trendflow.member.global.redis.session.*;
import com.trendflow.member.member.entity.Member;
import com.trendflow.member.member.repository.MemberRepository;
import com.trendflow.member.msa.service.CommonService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final LoginRefreshTokenRepository loginRefreshTokenRepository;
    private final LoginAccessTokenRepository loginAccessTokenRepository;
    private final MemberRepository memberRepository;
    private final KakaoAuthService kakaoAuthService;
    private final GoogleAuthService googleAuthService;
    private final CommonService commonService;

    public LoginResponse login(String platformCode, String authCode) throws RuntimeException {
        String KAKAO = commonService.getLocalCode(CommonCode.KAKAO.getName()).getCode();
        String GOOGLE = commonService.getLocalCode(CommonCode.GOOGLE.getName()).getCode();

        Member member = null;
        SocialUser socialUser = null;
        SocialAccess socialAccess = null;
        
        // 카카오 소셜 로그인
        if (KAKAO.equals(platformCode)){
            // accessToken 발급
            socialAccess = kakaoAuthService.getAccessToken(authCode);
            // accessToken 으로 카카오 사용자 정보 수집
            socialUser = kakaoAuthService.getUser(socialAccess.getAccessToken());
            // 카카오 사용자 정보를 통해 회원가입 여부 확인 (by Email)
            // 회원가입이 되어있으면 DB Member 반환
            // 회원가입이 안되어있으면 DB 에 등록 이후 Member 반환
            member = kakaoAuthService.getMember(socialUser);
        } 
        // 구글 소셜 로그인
        else if (GOOGLE.equals(platformCode)){
            socialAccess = googleAuthService.getAccessToken(authCode);
            socialUser = googleAuthService.getUser(socialAccess.getAccessToken());
            member = googleAuthService.getMember(socialUser);
        } 
        // 플랫폼 코드 인식 불가
        else throw new UnAuthException(AuthCode.PLATFORM_FAIL);

        // 세션 캐시에 로그인 정보 (RefreshToken) 저장
        loginRefreshTokenRepository.saveLogin(LoginRefreshToken.builder()
                .refreshToken(socialAccess.getRefreshToken())
                .refreshTokenExpire(socialAccess.getRefreshTokenExpire())
                .refreshExpire(LocalDateTime.now().plusSeconds(socialAccess.getRefreshTokenExpire()))
                .accessToken(socialAccess.getAccessToken())
                .accessTokenExpire(socialAccess.getAccessTokenExpire())
                .accessExpire(LocalDateTime.now().plusSeconds(socialAccess.getAccessTokenExpire()))
                .memberId(member.getMemberId())
                .platformCode(KAKAO)
                .platformUserId(socialUser.getKakaoUserId())
                .build());

        // 세션 캐시에 로그인 정보 (AccessToken) 저장
        loginAccessTokenRepository.save(LoginAccessToken.builder()
                .accessToken(socialAccess.getAccessToken())
                .accessExpire(LocalDateTime.now().plusSeconds(socialAccess.getAccessTokenExpire()))
                .refreshToken(socialAccess.getRefreshToken())
                .memberId(member.getMemberId())
                .isValid(true)
                .build());

        // DB 에 로그인 정보 (RefreshToken) 저장
        member.setRefreshToken(socialAccess.getRefreshToken());
        memberRepository.save(member);

        // 로그인 정보 응답 객체 생성
        return LoginResponse.builder()
                .name(member.getName())
                .accessToken(socialAccess.getAccessToken())
                .refreshToken(socialAccess.getRefreshToken())
                .build();
    }

    public RefreshTokenResponse refresh(String refreshToken) throws RuntimeException {

        String KAKAO = commonService.getLocalCode(CommonCode.KAKAO.getName()).getCode();
        String GOOGLE = commonService.getLocalCode(CommonCode.GOOGLE.getName()).getCode();

        // 세션 캐시에서 로그인 정보 조회
        LoginRefreshToken loginRefreshToken = loginRefreshTokenRepository.findById(refreshToken)
                .orElseThrow(() -> new UnAuthException(AuthCode.SEARCH_TOKEN_FAIL));

        SocialAccess socialAccess = null;

        if (KAKAO.equals(loginRefreshToken.getPlatformCode())) {
            socialAccess = kakaoAuthService.refreshAccessToken(
                    loginRefreshToken.getRefreshToken(),
                    loginRefreshToken.getRefreshTokenExpire());

        }
        // 구글 소셜 로그인
        else if (GOOGLE.equals(loginRefreshToken.getPlatformCode())){

            return null;
        }

        // 새로운 엑세스 토큰 등록
        loginAccessTokenRepository.save(LoginAccessToken.builder()
                .accessToken(socialAccess.getAccessToken())
                .accessExpire(LocalDateTime.now().plusSeconds(socialAccess.getAccessTokenExpire()))
                .refreshToken(socialAccess.getRefreshToken())
                .memberId(loginRefreshToken.getMemberId())
                .isValid(true)
                .build());

        // 새로운 토큰으로 변경
        loginRefreshTokenRepository.saveRefresh(LoginRefreshToken.builder()
                .refreshToken(socialAccess.getRefreshToken())
                .refreshTokenExpire(socialAccess.getRefreshTokenExpire())
                .refreshExpire(loginRefreshToken.getRefreshExpire())
                .accessToken(socialAccess.getAccessToken())
                .accessTokenExpire(socialAccess.getAccessTokenExpire())
                .accessExpire(LocalDateTime.now().plusSeconds(socialAccess.getAccessTokenExpire()))
                .memberId(loginRefreshToken.getMemberId())
                .platformCode(KAKAO)
                .platformUserId(loginRefreshToken.getPlatformUserId())
                .build());

        // DB 에 로그인 정보 (RefreshToken) 저장
        Member member = memberRepository.findById(loginRefreshToken.getMemberId())
                .orElseThrow(() -> new UnAuthException(AuthCode.KAKAO_GET_TOKEN_FAIL));
        member.setRefreshToken(socialAccess.getRefreshToken());
        memberRepository.save(member);

        // 기존의 엑세스 토큰을 무효화 시킴
        loginAccessTokenRepository.save(LoginAccessToken.builder()
                .accessToken(loginRefreshToken.getAccessToken())
                .accessExpire(loginRefreshToken.getAccessExpire())
                .refreshToken(socialAccess.getRefreshToken())
                .memberId(loginRefreshToken.getMemberId())
                .isValid(false)
                .build());

        return RefreshTokenResponse.builder()
                .accessToken(socialAccess.getAccessToken())
                .refreshToken(socialAccess.getRefreshToken())
                .build();
    }

    // 1단계 인증 (상위 인증)
    public void authAccessTokenToKakao(String accessToken) throws RuntimeException {

        String KAKAO = commonService.getLocalCode(CommonCode.KAKAO.getName()).getCode();
        String GOOGLE = commonService.getLocalCode(CommonCode.GOOGLE.getName()).getCode();

        LoginAccessToken loginAccessToken = loginAccessTokenRepository.findById(accessToken)
                .orElseThrow(() -> new UnAuthException(AuthCode.INVALID_TOKEN_FAIL));

        if (!loginAccessToken.getIsValid()) throw new UnAuthException(AuthCode.INVALID_TOKEN_FAIL);

        LoginRefreshToken loginRefreshToken = loginRefreshTokenRepository.findById(loginAccessToken.getRefreshToken())
                .orElseThrow(() -> new UnAuthException(AuthCode.INVALID_TOKEN_FAIL));

        // 카카오 토큰 인증
        if (KAKAO.equals(loginRefreshToken.getPlatformCode())) {
            KakaoTokenInfo kakaoTokenInfo = kakaoAuthService.authAccessToken(accessToken);

            // 토큰 인증 확인한 회원 ID가 현재 세션 캐시에 저장된 회원 ID와 다를 경우
            if (!kakaoTokenInfo.getId().equals(loginRefreshToken.getPlatformUserId())){
                throw new UnAuthException(AuthCode.INVALID_TOKEN_FAIL);
            }
        }
        // 구글 토큰 인증
        else if (GOOGLE.equals(loginRefreshToken.getPlatformCode())){
            GoogleTokenInfo googleTokenInfo = googleAuthService.authAccessToken(accessToken);

            if (!googleTokenInfo.getEmail())
        }
    }

    // 2단계 인증 (하위 인증)
    public void authAccessToken(String accessToken) throws RuntimeException {
        LoginAccessToken loginAccessToken = loginAccessTokenRepository.findById(accessToken)
                .orElseThrow(() -> new UnAuthException(AuthCode.INVALID_TOKEN_FAIL));

        if (!loginAccessToken.getIsValid()) throw new UnAuthException(AuthCode.INVALID_TOKEN_FAIL);

        LoginRefreshToken loginRefreshToken = loginRefreshTokenRepository.findById(loginAccessToken.getRefreshToken())
                .orElseThrow(() -> new UnAuthException(AuthCode.INVALID_TOKEN_FAIL));

        // 엑세스 토큰의 만료시간이 지난 경우 (토큰이 만료된 경우) / accessToken 만료 시킨 토큰인 경우
        if (LocalDateTime.now().isAfter(loginRefreshToken.getAccessExpire())){
            throw new UnAuthException(AuthCode.INVALID_TOKEN_FAIL);
        }
    }

    public void logout(String refreshToken) throws RuntimeException {

        String KAKAO = commonService.getLocalCode(CommonCode.KAKAO.getName()).getCode();
        String GOOGLE = commonService.getLocalCode(CommonCode.GOOGLE.getName()).getCode();

        LoginRefreshToken loginRefreshToken = loginRefreshTokenRepository.findById(refreshToken)
                .orElseThrow(() -> new UnAuthException(AuthCode.SEARCH_TOKEN_FAIL));

        LoginAccessToken loginAccessToken = loginAccessTokenRepository.findById(loginRefreshToken.getAccessToken())
                .orElseThrow(() -> new UnAuthException(AuthCode.SEARCH_TOKEN_FAIL));

        if (KAKAO.equals(loginRefreshToken.getPlatformCode())) {
            kakaoAuthService.expireToken(loginRefreshToken.getPlatformUserId());
        }
        // 구글 소셜 로그인
        else if (GOOGLE.equals(loginRefreshToken.getPlatformCode())){
            googleAuthService.expireToken(refreshToken);
        }

        // 현재 엑세스 토큰 만료 시킴
        loginAccessTokenRepository.save(LoginAccessToken.builder()
                .accessToken(loginAccessToken.getAccessToken())
                .accessExpire(loginAccessToken.getAccessExpire())
                .refreshToken(loginAccessToken.getRefreshToken())
                .memberId(loginAccessToken.getMemberId())
                .isValid(false)
                .build());
        // 현재 리프레시 토큰 삭제
        loginRefreshTokenRepository.delete(refreshToken);
    }
}