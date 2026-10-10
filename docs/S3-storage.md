# S3 객체 저장소 연결

프로필 이미지(A15·A16)와 OCR 원본 이미지(S01·S02)의 저장소를 AWS S3로 연결합니다.

## 문서 근거
- AI 서버 설계서: AWS S3, 서울 리전, 비공개 버킷. 임시 URL(presigned) 방식, AWS 키는 애플리케이션 서버 PC에만 둡니다.
- DB 설계서: DB에는 object key만 저장. 프로필 이미지 교체·삭제 시 이전 객체 삭제(실패 시 재처리), OCR 작업은 접수 24시간 후 임시 파일 삭제.
- API 명세서: `profileImageUrl`은 5분 유효 Presigned GET URL.

## 구현
| 파일 | 역할 |
| --- | --- |
| `global/storage/S3StorageConfig` | `S3Client`, `S3Presigner` 생성 (버킷 설정 시에만) |
| `member/business/S3ProfileImageStorage` | `ProfileImageStorage` 구현: put / delete / presignGet |
| `script/business/S3ScriptImageStorage` | `ScriptImageStorage` 구현: put / get / delete |
| `InMemoryProfileImageStorage`, `ScriptOcrConfig` | 버킷이 비어 있을 때만 임시 메모리 구현 사용 (테스트·로컬) |

객체 key는 기존 코드 그대로입니다: `profile-images/{userId}/{uuid}.{ext}`, `script-ocr/{userId}/{uuid}`.

## 설정
| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `STORAGE_S3_BUCKET` | (빈 값) | 비어 있으면 메모리 저장소 사용. **운영에서는 반드시 지정** |
| `STORAGE_S3_REGION` | `ap-northeast-2` | 서울 리전 |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | - | AWS 기본 자격 증명 체인으로 읽습니다 |

## 남은 사항
- 버킷 이름, IAM 권한(해당 prefix에 대한 Put/Get/Delete) 설정은 운영 환경에서 필요합니다.
- OCR 원본의 24시간 삭제는 기존 `script.ocr.purge-*` 스케줄러가 `delete`를 호출합니다.
- 실제 S3 연결(버킷 지정 상태)은 자동 테스트하지 않았습니다. 테스트는 메모리 구현으로 실행됩니다.
