package com.spring.blog.post.service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spring.blog.common.exception.BaseException;
import com.spring.blog.common.util.PagingResponse;
import com.spring.blog.post.dto.Pagination;
import com.spring.blog.post.dto.SearchDTO;
import com.spring.blog.post.exception.InvalidOrderTypeException;
import com.spring.blog.post.exception.PostNotFoundException;
import com.spring.blog.post.exception.ViewCountIncrementException;
import com.spring.blog.post.mapper.CommentMapper;
import com.spring.blog.post.mapper.PostMapper;
import com.spring.blog.post.mapper.RecommendMapper;
import com.spring.blog.post.vo.CommentVO;
import com.spring.blog.post.vo.PostVO;
import com.spring.blog.post.vo.RecommendVO;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class PostServiceImpl implements PostService{

	private Logger logger = LoggerFactory.getLogger(this.getClass());

	@Autowired
	private PostMapper postMapper;

	@Autowired
	private RecommendMapper recommendMapper;

	@Autowired
	private CommentMapper commentMapper;	

	/*
	 * 단일 포스트 조회
	 */
	@Transactional
	@Override
	public PostVO getPostById(Long id, boolean incrementViewCount){

		try {
			PostVO post = postMapper.findById(id);
			if(post == null) {
				throw new PostNotFoundException(id);
			}else{
				//조회 수 증가
				if(incrementViewCount) {
					increaseViewCount(id);
				}
			}

			return PostVO.from(post);
		}catch(PostNotFoundException e) {
			throw e;
		}catch(Exception e) {
			logger.error(e.getMessage(), e);
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred while fetching the post with id: " + id);
		}
	}

	/*
	 * 전체 포스트 조회
	 */
	@Transactional(readOnly=true)
	@Override
	public PagingResponse<PostVO> getPostByAll(SearchDTO params) {

		try {
			//정렬 타입에 맞게 동적 쿼리 생성
			params = setOrderColumn(params);

			// 조건에 해당하는 데이터가 없는 경우, 응답 데이터에 비어있는 리스트와 null을 담아 반환
			long count = getPostByAllCount(params);
			if(count < 1) {
				Pagination emptyPagination = new Pagination();
				emptyPagination.setTotalRecordCount(0);
				emptyPagination.setTotalPageCount(0);
				emptyPagination.setStartPage(0);
				emptyPagination.setEndPage(0);
				emptyPagination.setLimitStart(0);
				emptyPagination.setExistPrevPage(false);
				emptyPagination.setExistNextPage(false);
				return new PagingResponse<>(Collections.emptyList(), emptyPagination);
			}

			// Pagination 객체를 생성해서 페이지 정보 계산 후 SearchDto 타입의 객체인 params에 계산된 페이지 정보 저장
			Pagination pagination = new Pagination(count, params);
			params.setPagination(pagination);

			// 계산된 페이지 정보의 일부(limitStart, recordSize)를 기준으로 리스트 데이터 조회 후 응답 데이터 반환
			List<PostVO> posts = postMapper.findByAll(params);
			return new PagingResponse<>(posts, pagination);

		} catch (InvalidOrderTypeException e) {
			logger.error(e.getMessage(), e);
			throw e;
		} catch (Exception e) {
			logger.error("An unexpected error occurred", e);
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
		}

	}

	/*
	 * 전체 포스트 조회 연관 메서드
	 */

	//정렬 타입 설정
	private SearchDTO setOrderColumn(SearchDTO params) {
		/*
		 * orderType: 정렬 기준
		 * 1: 최신순
		 * 2: 오래된순
		 * 3: 조회순
		 * 4: 추천순
		 */
		switch(params.getOrderType()) {
		case 1:
			params.setOrderCol("write_date DESC");
			return params;
		case 2:
			params.setOrderCol("write_date ASC");
			return params;
		case 3:
			params.setOrderCol("select_count DESC");
			return params;
		case 4:
			params.setOrderCol("recom_count DESC");
			return params;
		default: 
			throw new InvalidOrderTypeException(params.getOrderType());
		}

	}


	/*
	 * 전체 포스트 개수 조회
	 */
	@Transactional(readOnly=true)
	@Override
	public long getPostByAllCount(SearchDTO params) {
		try {
			return postMapper.findByAllCount(params);
		} catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to retrieve post count");
		}
	}


	/*
	 * 포스트 등록
	 */
	@Transactional
	@Override
	public void createPost(PostVO post) {

		try {
			post.setDeleteYn("N");
			LocalDateTime now = LocalDateTime.now();
			post.setWriteDate(now);
			post.setUpdateDate(now);
			post.setSelectCount(0);
			post.setRecomCount(0);

			vaildationPost(post); //유효성 검사
			postMapper.createPost(post);
		}catch (IllegalArgumentException e) {
			throw new BaseException(HttpStatus.BAD_REQUEST,"Invalid post data: " + e.getMessage());
		}catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR,"Database access error: " + e.getMessage());
		}catch(BaseException e) {
			throw e;
		}catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR,"Unexpected error occurred: " + e.getMessage());
		}
	}

	private static final String GRAMMAR_API_URL = "https://api.languagetool.org/v2/check";
	private final RestTemplate restTemplate = new RestTemplate();
	/*
		문법 검사
	 */
	@Override
	public Map<String, Object> getCheckGrammar(String text) {
		// 외부 API 호출에 필요한 파라미터 설정
		MultiValueMap<String, String> apiParams = new LinkedMultiValueMap<>();
		apiParams.add("text", text);
		apiParams.add("language", "en-US");

		// 외부 API 호출 및 응답 받기
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
			HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(apiParams, headers);
			Map<String, Object> response = restTemplate.postForObject(GRAMMAR_API_URL, requestEntity, Map.class);
			// matches 항목을 사용해 문법 교정 제안 반환
			Map<String, Object> result = new HashMap<>();
			result.put("matches", response != null ? response.get("matches") : null);
			return result;
		} catch (Exception e) {
			logger.info(e.getMessage());
			return new HashMap<>();
		}
	}

	//포스트 유효성 검사
	private void vaildationPost(PostVO post) {
		if (post.getTitle() == null || post.getTitle().isEmpty()) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Post title cannot be null or empty");
		}
		if (post.getWriter() == null || post.getWriter().isEmpty()) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Post writer cannot be null or empty");
		}
		if (post.getCategory() == null || post.getCategory().isEmpty()) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Post category cannot be null or empty");
		}
		if (post.getContent() == null || post.getContent().isEmpty()) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Post content cannot be null or empty");
		}
	}

	/*
	 * 포스트 업데이트
	 */
	@Transactional
	@Override
	public void updatePost(PostVO post) {

		try {
			post.setUpdateDate(LocalDateTime.now());
			vaildationPost(post); //유효성 검사
			int affectedRows = postMapper.updatePost(post);
			if (affectedRows == 0) {
				throw new PostNotFoundException(post.getId());
			}
		}catch (IllegalArgumentException e) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Invalid post data: " + e.getMessage());
		}catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR,"Database access error: " + e.getMessage());
		}catch(BaseException e) {
			throw e;
		}catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
		}
	}


	/*
	 * 포스팅 삭제
	 */
	@Transactional
	@Override
	public void deletePost(Long id) {

		try {
			PostVO post = new PostVO();
			post.setId(id);
			post.setDeleteDate(LocalDateTime.now());
			post.setDeleteYn("Y");

			int affectedRows = postMapper.deletePost(post);
			if(affectedRows == 0){
				throw new PostNotFoundException(post.getId());
			}
		}catch (IllegalArgumentException e) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Invalid post data: " + e.getMessage());
		}catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
		}catch(BaseException e) {
			throw e;
		}catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
		}
	}

	/*
	 * 포스트 조회수 증가
	 */
	@Transactional
	@Override
	public void increaseViewCount(Long id) {
		int affectedRow = postMapper.increaseViewCount(id);
		if(affectedRow == 0) {
			throw new  ViewCountIncrementException(id);
		}
	}

	/*
	 * ---------------------------아래부터 추천 테이블 관련----------------------------------- 
	 */

	/*
	 * 추천 UP/DOWN
	 */
	@Transactional
	@Override
	public void processRecommendation(RecommendVO recommend) {
		try {
			int recomPost; //추천 여부
			int recomPostUserInfo; //사용자 추천 여부

			if (recommend.getCountFlag() == 1) { // UP
				recomPost = recomCountIncrease(recommend.getPostId());
				recomPostUserInfo = recomUserInfoUpdate(recommend);
			} else { // DOWN
				recomPost = recomCountDecrease(recommend.getPostId());
				recomPostUserInfo = recomUserInfoDelete(recommend);
			}

			if (recomPost != 1 || recomPostUserInfo != 1) {
				throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Post recommendation failed");
			}
		} catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
		} catch (BaseException e) {
			throw e;
		} catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
		}
	}

	/*
	 * 포스트 추천수 증가
	 */
	@Transactional
//	@Override
	public int recomCountIncrease(Long postId) {
		try {
			return postMapper.updateRecomCount(postId);
		} catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to increase recommendation count");
		}
	}

	/*
	 * 포스트 추천수 감소
	 */
	@Transactional
//	@Override
	public int recomCountDecrease(Long postId) {
		try {
			return postMapper.deleteRecomCount(postId);
		} catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to decrease recommendation count");
		}
	}

	/*
	 * 포스트 추천 사용자 정보 저장
	 */
	@Transactional
//	@Override
	public int recomUserInfoUpdate(RecommendVO recommend) {
		try {
			recommend.setRecommendDate(LocalDateTime.now());
			recommend.setDeleteYn("N");
			return recommendMapper.updateRecomUserInfo(recommend);
		} catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update recommendation user info");
		}
	}

	/*
	 * 포스트 추천 사용자 정보 삭제
	 */
	@Transactional
//	@Override
	public int recomUserInfoDelete(RecommendVO recommend) {
		try {
			recommend.setDeleteDate(LocalDateTime.now());
			recommend.setDeleteYn("Y");
			return recommendMapper.deleteRecomUserInfo(recommend);
		} catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete recommendation user info");
		}
	}

	/*
	 * 추천 사용자 단일 조회
	 */
	@Transactional(readOnly=true)
	@Override
	public RecommendVO getPostRecomByUserIdAndPostId(String userId, Long postId) {
		try {
	        RecommendVO recommend = new RecommendVO();
	        recommend.setUserId(userId);
	        recommend.setPostId(postId);
	        
	        RecommendVO recom = recommendMapper.findByUserIdAndPostId(recommend);
	        if (recom == null) { //null 값으로 에러가 아님을 전달
	        	return null;
	        }
	        return RecommendVO.from(recom);
	    } catch (DataAccessException e) {
	        throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
	    } catch (BaseException e) {
	        throw e;
	    } catch (Exception e) {
	        throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
	    }
	}

	/*
	 * 댓글 등록
	 */
	@Transactional
	@Override
	public void createComment(CommentVO comment) {
		try {
			comment.setDeleteYn("N");
			LocalDateTime now = LocalDateTime.now();
			comment.setCommentDate(now);
			
			commentMapper.createComment(comment);
		}catch (IllegalArgumentException e) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Invalid comment data: " + e.getMessage());
		}catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
		}catch(BaseException e) {
			throw e;
		}catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
		}
	}

	/*
	 * 댓글 조회
	 */
	@Transactional(readOnly=true)
	@Override
	public List<CommentVO> getCommentById(Long id) {

		try {
			return commentMapper.findById(id);
		} catch (DataAccessException e) {
	        throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
	    } catch (BaseException e) {
	        throw e;
	    } catch (Exception e) {
	        throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
	    }
	}

	/*
	 * 댓글 삭제
	 */
	@Override
	public void deleteComment(Long commentId) {
		try {
			CommentVO comment = new CommentVO();
			comment.setId(commentId);
			comment.setDeleteDate(LocalDateTime.now());
			comment.setDeleteYn("Y");
			commentMapper.deleteComment(comment);
		}catch (IllegalArgumentException e) {
			throw new BaseException(HttpStatus.BAD_REQUEST, "Invalid post data: " + e.getMessage());
		}catch (DataAccessException e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Database access error: " + e.getMessage());
		}catch(BaseException e) {
			throw e;
		}catch (Exception e) {
			throw new BaseException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error occurred: " + e.getMessage());
		}
	}

}
