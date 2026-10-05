-- GENERATED FROM openapi.yaml / x-database. Do not edit.
-- V1.2 isolated SIMULATION schema only. NOT a migration for existing Yudao.
-- Candidate target MySQL 8.4; enforce CHECK (MySQL >=8.0.16).
-- Execute in a disposable, explicitly selected empty database. No CREATE DATABASE or DROP.
SET NAMES utf8mb4;

CREATE TABLE `sim_user` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `uid` CHAR(9) NOT NULL COMMENT 'y+8位数字',
  `phone_cipher` VARBINARY(512) NOT NULL COMMENT '加密手机号；合成账户使用专用Mock标识',
  `phone_lookup` CHAR(64) NOT NULL COMMENT '规范化手机号的带密钥检索标记',
  `display_name` VARCHAR(64) NOT NULL COMMENT '用户昵称，不用于账号合并',
  `status` ENUM('ACTIVE','SUSPENDED','CLOSED') NOT NULL DEFAULT 'ACTIVE' COMMENT '状态',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '乐观锁',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_user_1` (`uid`),
  UNIQUE KEY `uq_sim_user_2` (`phone_lookup`),
  KEY `ix_sim_user_1` (`status`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_identity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属平台用户',
  `provider` VARCHAR(32) NOT NULL COMMENT 'WECHAT_MINIAPP',
  `app_id` VARCHAR(64) NOT NULL COMMENT '身份应用范围',
  `subject_cipher` VARBINARY(512) NOT NULL COMMENT '加密OpenID',
  `subject_lookup` CHAR(64) NOT NULL COMMENT '带密钥检索标记',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_identity_1` (`provider`, `app_id`, `subject_lookup`),
  UNIQUE KEY `uq_sim_identity_2` (`user_id`, `provider`, `app_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
  `refresh_hash` CHAR(64) NOT NULL COMMENT '刷新令牌摘要',
  `family_id` CHAR(36) NOT NULL COMMENT '重用检测会话族',
  `expires_at` DATETIME(3) NOT NULL COMMENT '到期',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_session_1` (`refresh_hash`),
  KEY `ix_sim_session_1` (`user_id`, `expires_at`),
  KEY `ix_sim_session_2` (`family_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_sms_challenge` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `challenge_id` CHAR(36) NOT NULL COMMENT '对外UUID',
  `phone_lookup` CHAR(64) NOT NULL COMMENT '绑定手机号',
  `purpose` ENUM('REGISTER','LOGIN','REBIND') NOT NULL COMMENT '用途不可串用',
  `code_hash` CHAR(64) NOT NULL COMMENT '验证码摘要',
  `attempts` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败计数',
  `expires_at` DATETIME(3) NOT NULL COMMENT '有效期',
  `consumed_at` DATETIME(3) NULL COMMENT '单次消费',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_sms_challenge_1` (`challenge_id`),
  KEY `ix_sim_sms_challenge_1` (`phone_lookup`, `purpose`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_consent` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '本人',
  `purpose` VARCHAR(40) NOT NULL COMMENT 'REGISTER/SIMULATION/EXPORT等',
  `document_version` VARCHAR(40) NOT NULL COMMENT '协议版本',
  `accepted_at` DATETIME(3) NOT NULL COMMENT '主动选择时刻',
  `withdrawn_at` DATETIME(3) NULL COMMENT '撤回',
  `document_hash` CHAR(64) NOT NULL COMMENT '展示内容摘要',
  PRIMARY KEY (`id`),
  KEY `ix_sim_consent_1` (`user_id`, `purpose`, `accepted_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_merchant` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `uid` CHAR(8) NOT NULL COMMENT 's+7位数字',
  `owner_user_id` BIGINT UNSIGNED NOT NULL COMMENT '老板账户',
  `name` VARCHAR(128) NOT NULL COMMENT '显示名',
  `status` ENUM('DRAFT','APPROVED','SUSPENDED','EXITING','EXITED') NOT NULL COMMENT '经营状态',
  `contract_version` VARCHAR(40) NOT NULL COMMENT '模拟参与协议',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_merchant_1` (`uid`),
  KEY `ix_sim_merchant_1` (`owner_user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_merchant_application` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '待审核主体',
  `applicant_id` BIGINT UNSIGNED NOT NULL COMMENT '发起用户',
  `material_version` INT UNSIGNED NOT NULL COMMENT '资料版本',
  `material_summary` JSON NOT NULL COMMENT '仅模拟主体资料，不放真实证件',
  `status` ENUM('DRAFT','SUBMITTED','NEEDS_INFO','APPROVED','REJECTED') NOT NULL COMMENT '流程',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '独立审核人',
  `review_reason` VARCHAR(500) NULL COMMENT '结论理由',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_merchant_application_1` (`merchant_id`, `material_version`),
  KEY `ix_sim_merchant_application_1` (`status`, `created_at`),
  CONSTRAINT `ck_sim_merchant_application_1` CHECK (reviewer_id IS NULL OR reviewer_id <> applicant_id),
  CONSTRAINT `ck_sim_merchant_application_2` CHECK (status NOT IN ('APPROVED','REJECTED','NEEDS_INFO') OR reviewer_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_store` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '主体',
  `name` VARCHAR(100) NOT NULL COMMENT '门店名称',
  `region_code` VARCHAR(16) NOT NULL COMMENT '手动地区码，无用户GPS',
  `address_text` VARCHAR(200) NOT NULL COMMENT '模拟地址',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '停用保留历史',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_store_1` (`id`, `merchant_id`),
  KEY `ix_sim_store_1` (`merchant_id`, `active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_role_assignment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '被授权人',
  `scope_key` VARCHAR(100) NOT NULL COMMENT 'PLATFORM或MERCHANT:id或STORE:id；服务端规范化',
  `role_code` ENUM('OWNER','CLERK','MERCHANT_REVIEWER','LEDGER_MAKER','LEDGER_CHECKER','SUPPORT','SECURITY','SIM_CONTROLLER') NOT NULL COMMENT '角色',
  `expires_at` DATETIME(3) NULL COMMENT '授权期限',
  `revoked_at` DATETIME(3) NULL COMMENT '撤销',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_role_assignment_1` (`user_id`, `scope_key`, `role_code`),
  KEY `ix_sim_role_assignment_1` (`scope_key`, `role_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_product` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '商家',
  `store_id` BIGINT UNSIGNED NOT NULL COMMENT '门店',
  `name` VARCHAR(128) NOT NULL COMMENT '套餐名',
  `price_minor` BIGINT UNSIGNED NOT NULL COMMENT 'CNY分',
  `active` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '在架',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '价格快照版本',
  PRIMARY KEY (`id`),
  KEY `ix_sim_product_1` (`store_id`, `active`, `id`),
  CONSTRAINT `ck_sim_product_1` CHECK (price_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_rule_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `code` VARCHAR(40) NOT NULL COMMENT 'RV-SIM-001等',
  `status` ENUM('DRAFT','APPROVED','RETIRED') NOT NULL COMMENT '版本流程',
  `quota_minor` BIGINT UNSIGNED NOT NULL COMMENT '初始10000分',
  `transaction_max_minor` BIGINT UNSIGNED NOT NULL COMMENT '单笔2000分',
  `term_days` SMALLINT UNSIGNED NOT NULL COMMENT '30天',
  `credential_ttl_seconds` INT UNSIGNED NOT NULL COMMENT '确认凭证300秒',
  `reservation_ttl_seconds` INT UNSIGNED NOT NULL COMMENT '预占900秒',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '提交者',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '复核者',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_rule_version_1` (`code`),
  CONSTRAINT `ck_sim_rule_version_1` CHECK (transaction_max_minor <= quota_minor),
  CONSTRAINT `ck_sim_rule_version_2` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_rule_version_3` CHECK (status = 'DRAFT' OR checker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属商家',
  `store_id` BIGINT UNSIGNED NOT NULL COMMENT '所属门店',
  `user_id` BIGINT UNSIGNED NULL COMMENT '确认后绑定消费者',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '创建者',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '快照规则',
  `total_minor` BIGINT UNSIGNED NOT NULL COMMENT '由服务端计算',
  `currency` CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '固定人民币模拟单位',
  `status` ENUM('DRAFT','PUBLISHED','CONFIRMED','FULFILLED','CANCELLED','EXPIRED') NOT NULL COMMENT '订单状态',
  `fulfillment_status` ENUM('NOT_READY','READY','FULFILLED','VOID') NOT NULL COMMENT '履约状态',
  `snapshot_hash` CHAR(64) NOT NULL COMMENT '内容摘要',
  `published_expires_at` DATETIME(3) NULL COMMENT '发布确认截止',
  `confirmed_at` DATETIME(3) NULL COMMENT '确认时刻',
  `fulfilled_at` DATETIME(3) NULL COMMENT '履约时刻',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'If-MatchVersion',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_order_1` (`id`, `merchant_id`),
  KEY `ix_sim_order_1` (`user_id`, `created_at`, `id`),
  KEY `ix_sim_order_2` (`merchant_id`, `status`, `created_at`),
  CONSTRAINT `ck_sim_order_1` CHECK (total_minor > 0),
  CONSTRAINT `ck_sim_order_2` CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `line_no` SMALLINT UNSIGNED NOT NULL COMMENT '行序号',
  `product_id` BIGINT UNSIGNED NOT NULL COMMENT '商品引用',
  `product_name` VARCHAR(128) NOT NULL COMMENT '名称快照',
  `quantity` INT UNSIGNED NOT NULL COMMENT '整数数量',
  `unit_price_minor` BIGINT UNSIGNED NOT NULL COMMENT '单价快照',
  `subtotal_minor` BIGINT UNSIGNED NOT NULL COMMENT '数量乘单价',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_order_item_1` (`order_id`, `line_no`),
  CONSTRAINT `ck_sim_order_item_1` CHECK (quantity > 0),
  CONSTRAINT `ck_sim_order_item_2` CHECK (subtotal_minor = quantity * unit_price_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_credential` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '所属订单',
  `purpose` ENUM('CONFIRM','FULFILL') NOT NULL COMMENT '不可跨用途',
  `nonce_hash` CHAR(64) NOT NULL COMMENT '随机凭证摘要',
  `bound_user_id` BIGINT UNSIGNED NULL COMMENT '确认后核销凭证绑定本人',
  `order_version` INT UNSIGNED NOT NULL COMMENT '快照版本',
  `expires_at` DATETIME(3) NOT NULL COMMENT '到期',
  `consumed_at` DATETIME(3) NULL COMMENT '已使用',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_credential_1` (`nonce_hash`),
  KEY `ix_sim_credential_1` (`order_id`, `purpose`, `expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_limit_account` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '全平台用户',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '额度规则',
  `total_minor` BIGINT UNSIGNED NOT NULL COMMENT '总模拟额度',
  `reserved_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '有效预占',
  `principal_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '未还本金',
  `blocked` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '仅暂停新增',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '原子更新',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_limit_account_1` (`user_id`),
  CONSTRAINT `ck_sim_limit_account_1` CHECK (reserved_minor + principal_minor <= total_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_reservation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单唯一预占',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '额度主体',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '预占金额',
  `status` ENUM('HELD','CONSUMED','RELEASED','EXPIRED') NOT NULL COMMENT '状态',
  `expires_at` DATETIME(3) NOT NULL COMMENT '服务端到期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_reservation_1` (`order_id`),
  KEY `ix_sim_reservation_1` (`status`, `expires_at`),
  CONSTRAINT `ck_sim_reservation_1` CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_receivable` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '履约订单',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '模拟消费者',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '模拟应收所属商家',
  `issued_minor` BIGINT UNSIGNED NOT NULL COMMENT '原始本金',
  `outstanding_minor` BIGINT UNSIGNED NOT NULL COMMENT '当前剩余本金',
  `due_at` DATETIME(3) NOT NULL COMMENT '履约后30天',
  `status` ENUM('OPEN','PARTIAL','SETTLED') NOT NULL COMMENT '与期限/争议分离',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_receivable_1` (`order_id`),
  UNIQUE KEY `uq_sim_receivable_2` (`id`, `merchant_id`),
  KEY `ix_sim_receivable_1` (`user_id`, `due_at`, `id`),
  KEY `ix_sim_receivable_2` (`merchant_id`, `status`, `due_at`),
  CONSTRAINT `ck_sim_receivable_1` CHECK (outstanding_minor <= issued_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_repayment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '消费者',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '同一商家分配范围',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '拟还金额',
  `received_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已核实金额',
  `unallocated_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '待分配/待退',
  `method` ENUM('MOCK_ONLINE','MOCK_OFFLINE') NOT NULL COMMENT '仅模拟渠道',
  `status` ENUM('CREATED','PENDING','CONFIRMED','FAILED','CLOSED','EXCEPTION') NOT NULL COMMENT '状态',
  `reference` VARCHAR(80) NOT NULL COMMENT '模拟渠道业务号',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_repayment_1` (`reference`),
  UNIQUE KEY `uq_sim_repayment_2` (`id`, `merchant_id`),
  KEY `ix_sim_repayment_1` (`user_id`, `created_at`),
  KEY `ix_sim_repayment_2` (`merchant_id`, `status`),
  CONSTRAINT `ck_sim_repayment_1` CHECK (amount_minor > 0),
  CONSTRAINT `ck_sim_repayment_2` CHECK (unallocated_minor <= received_minor)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_repayment_allocation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `repayment_id` BIGINT UNSIGNED NOT NULL COMMENT '还款单',
  `receivable_id` BIGINT UNSIGNED NOT NULL COMMENT '应收',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '同一商家',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '实际核销本金',
  `event_key` VARCHAR(100) NOT NULL COMMENT '事件去重',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_repayment_allocation_1` (`event_key`),
  KEY `ix_sim_repayment_allocation_1` (`receivable_id`, `id`),
  CONSTRAINT `ck_sim_repayment_allocation_1` CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_offline_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `repayment_id` BIGINT UNSIGNED NOT NULL COMMENT '线下还款单',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '登记人',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '用户或独立复核员',
  `evidence_refs` JSON NOT NULL COMMENT '已核验私有文件ID列表',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '模拟收款时间',
  `status` ENUM('SUBMITTED','VERIFIED','REJECTED') NOT NULL COMMENT '状态',
  `reason` VARCHAR(500) NULL COMMENT '复核原因',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_offline_review_1` (`repayment_id`),
  CONSTRAINT `ck_sim_offline_review_1` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_offline_review_2` CHECK (status = 'SUBMITTED' OR checker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '原订单',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '所属商家',
  `reference` VARCHAR(80) NOT NULL COMMENT '模拟退款业务号',
  `requested_minor` BIGINT UNSIGNED NOT NULL COMMENT '退货总额',
  `principal_reduction_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '批准时锁定冲本金',
  `return_payable_minor` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '批准时应退用户',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '发起人',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '独立审核',
  `status` ENUM('REQUESTED','APPROVED','REJECTED','PROCESSING','SUCCEEDED','FAILED','EXCEPTION') NOT NULL COMMENT '退款状态',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '并发版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_refund_1` (`reference`),
  KEY `ix_sim_refund_1` (`order_id`, `status`),
  CONSTRAINT `ck_sim_refund_1` CHECK (requested_minor > 0),
  CONSTRAINT `ck_sim_refund_2` CHECK (principal_reduction_minor + return_payable_minor <= requested_minor),
  CONSTRAINT `ck_sim_refund_3` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_refund_4` CHECK (status = 'REQUESTED' OR checker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_dispute` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '原订单',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '本人',
  `reason` VARCHAR(1000) NOT NULL COMMENT '异议内容',
  `evidence_refs` JSON NOT NULL COMMENT '证据ID',
  `assignee_id` BIGINT UNSIGNED NULL COMMENT '处理者',
  `status` ENUM('OPEN','REVIEWING','RESOLVED','APPEALED','CLOSED') NOT NULL COMMENT '独立状态',
  `resolution` VARCHAR(1000) NULL COMMENT '结论，不在此直接改金额',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本',
  PRIMARY KEY (`id`),
  KEY `ix_sim_dispute_1` (`user_id`, `created_at`),
  KEY `ix_sim_dispute_2` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_book` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '模拟商家',
  `book_code` VARCHAR(40) NOT NULL COMMENT 'SIM-OP-商家号',
  `currency` CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种',
  `last_sequence` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '账簿内单调序号，入账事务持锁分配',
  `closed_through` DATE NULL COMMENT '已连续关闭到的上海业务日，派生缓存，不替代日状态',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_book_1` (`book_code`),
  UNIQUE KEY `uq_sim_book_2` (`merchant_id`),
  CONSTRAINT `ck_sim_book_1` CHECK (currency = 'CNY')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_journal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `book_id` BIGINT UNSIGNED NOT NULL COMMENT '账簿',
  `event_key` VARCHAR(100) NOT NULL COMMENT '可追溯业务事件',
  `template_code` VARCHAR(20) NOT NULL COMMENT 'J-01等',
  `source_type` VARCHAR(40) NOT NULL COMMENT 'ORDER/REPAYMENT/REFUND/ADJUSTMENT',
  `source_id` BIGINT UNSIGNED NOT NULL COMMENT '来源单据ID；服务层校验',
  `occurred_at` DATETIME(3) NOT NULL COMMENT '业务时刻',
  `posted_at` DATETIME(3) NOT NULL COMMENT '入账时刻',
  `debit_total_minor` BIGINT UNSIGNED NOT NULL COMMENT '借方合计',
  `credit_total_minor` BIGINT UNSIGNED NOT NULL COMMENT '贷方合计',
  `reversal_of` BIGINT UNSIGNED NULL COMMENT '全额冲回原分录，可为空',
  `business_day_id` BIGINT UNSIGNED NOT NULL COMMENT '当前入账日；不能由客户端回填历史关闭日',
  `sequence_no` BIGINT UNSIGNED NOT NULL COMMENT '账簿内单调序号',
  `occurred_business_date` DATE NOT NULL COMMENT '原业务日期，按Asia/Shanghai由occurred_at换算',
  `posting_date` DATE NOT NULL COMMENT '本次入账日期，当前可写业务日',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_journal_1` (`book_id`, `event_key`),
  UNIQUE KEY `uq_sim_journal_2` (`book_id`, `sequence_no`),
  KEY `ix_sim_journal_1` (`source_type`, `source_id`),
  KEY `ix_sim_journal_2` (`book_id`, `posted_at`),
  KEY `ix_sim_journal_3` (`book_id`, `posting_date`, `sequence_no`),
  CONSTRAINT `ck_sim_journal_1` CHECK (debit_total_minor = credit_total_minor),
  CONSTRAINT `ck_sim_journal_2` CHECK (debit_total_minor > 0),
  CONSTRAINT `ck_sim_journal_3` CHECK (sequence_no > 0),
  CONSTRAINT `ck_sim_journal_4` CHECK (posting_date >= occurred_business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_journal_line` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `journal_id` BIGINT UNSIGNED NOT NULL COMMENT '分录批次',
  `line_no` SMALLINT UNSIGNED NOT NULL COMMENT '序号',
  `account_code` ENUM('SIM_AR','SIM_RECEIPT','SIM_SALES_CTRL','SIM_REFUND_CTRL','SIM_RETURN_PAYABLE') NOT NULL COMMENT '模拟科目',
  `side` ENUM('DR','CR') NOT NULL COMMENT '借贷方向',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '正数',
  `user_id` BIGINT UNSIGNED NULL COMMENT '用户维度',
  `order_id` BIGINT UNSIGNED NULL COMMENT '订单维度',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_journal_line_1` (`journal_id`, `line_no`),
  KEY `ix_sim_journal_line_1` (`user_id`, `account_code`),
  CONSTRAINT `ck_sim_journal_line_1` CHECK (amount_minor > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_adjustment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `original_journal_id` BIGINT UNSIGNED NOT NULL COMMENT '原事件',
  `receivable_id` BIGINT UNSIGNED NOT NULL COMMENT '应收',
  `kind` ENUM('DOWNWARD','DUPLICATE_REVERSAL') NOT NULL COMMENT '不允许任意新增长期债务',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '拟调整正数',
  `reason` VARCHAR(1000) NOT NULL COMMENT '原因与影响',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '发起人',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '复核人',
  `status` ENUM('REQUESTED','APPROVED','REJECTED','POSTED') NOT NULL COMMENT '状态',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '版本',
  PRIMARY KEY (`id`),
  KEY `ix_sim_adjustment_1` (`status`, `created_at`),
  CONSTRAINT `ck_sim_adjustment_1` CHECK (amount_minor > 0),
  CONSTRAINT `ck_sim_adjustment_2` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_adjustment_3` CHECK (status = 'REQUESTED' OR checker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_inbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `provider_event_id` VARCHAR(100) NOT NULL COMMENT '事件唯一号',
  `key_id` VARCHAR(40) NOT NULL COMMENT '签名密钥版本',
  `body_hash` CHAR(64) NOT NULL COMMENT '原始UTF8正文摘要',
  `reference` VARCHAR(80) NOT NULL COMMENT '模拟业务号',
  `amount_minor` BIGINT UNSIGNED NOT NULL COMMENT '核实金额',
  `event_type` ENUM('PAYMENT_CONFIRMED','REFUND_CONFIRMED') NOT NULL COMMENT '事件',
  `status` ENUM('RECEIVED','APPLIED','EXCEPTION') NOT NULL COMMENT '入站状态',
  `payload` JSON NOT NULL COMMENT '最小合成数据',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_inbox_1` (`provider_event_id`),
  KEY `ix_sim_inbox_1` (`reference`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_idempotency` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `actor_key` VARCHAR(80) NOT NULL COMMENT '服务端身份，不接受前端角色',
  `operation_id` VARCHAR(64) NOT NULL COMMENT '固定契约操作ID',
  `key_hash` CHAR(64) NOT NULL COMMENT '幂等键摘要',
  `request_hash` CHAR(64) NOT NULL COMMENT '规范化业务请求摘要',
  `status` ENUM('PROCESSING','COMPLETED','FAILED') NOT NULL COMMENT '执行状态',
  `response_status` SMALLINT UNSIGNED NULL COMMENT '原HTTP状态',
  `response_body` JSON NULL COMMENT '不存原始token/验证码',
  `resource_id` VARCHAR(64) NULL COMMENT '业务资源',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_idempotency_1` (`actor_key`, `operation_id`, `key_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_outbox` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `event_key` VARCHAR(100) NOT NULL COMMENT '事件唯一',
  `aggregate_type` VARCHAR(40) NOT NULL COMMENT '聚合名',
  `aggregate_id` BIGINT UNSIGNED NOT NULL COMMENT '聚合ID',
  `payload` JSON NOT NULL COMMENT '最小消息',
  `status` ENUM('PENDING','SENT','DEAD') NOT NULL COMMENT '投递状态',
  `attempts` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '重试计数',
  `next_attempt_at` DATETIME(3) NOT NULL COMMENT '下次尝试',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_outbox_1` (`event_key`),
  KEY `ix_sim_outbox_1` (`status`, `next_attempt_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_file` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `owner_id` BIGINT UNSIGNED NOT NULL COMMENT '上传人',
  `purpose` ENUM('SIM_EVIDENCE','RECON_IMPORT','EXPORT') NOT NULL COMMENT '用途',
  `object_key` VARCHAR(255) NOT NULL COMMENT '私有存储key，不接收任意URL',
  `mime_type` VARCHAR(80) NOT NULL COMMENT '类型',
  `size_bytes` BIGINT UNSIGNED NOT NULL COMMENT '大小',
  `sha256` CHAR(64) NOT NULL COMMENT '内容摘要',
  `status` ENUM('QUARANTINED','READY','REJECTED','DELETED') NOT NULL COMMENT '检测后可用',
  `expires_at` DATETIME(3) NULL COMMENT '到期',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_file_1` (`object_key`),
  KEY `ix_sim_file_1` (`owner_id`, `purpose`),
  CONSTRAINT `ck_sim_file_1` CHECK (size_bytes <= 10485760)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_reconciliation` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `merchant_id` BIGINT UNSIGNED NOT NULL COMMENT '商家',
  `business_date` DATE NOT NULL COMMENT 'Asia/Shanghai业务日',
  `file_id` BIGINT UNSIGNED NOT NULL COMMENT '模拟文件',
  `file_hash` CHAR(64) NOT NULL COMMENT '原文件摘要',
  `status` ENUM('CREATED','RUNNING','MATCHED','DIFFERENCE','RESOLVED','FAILED') NOT NULL COMMENT '状态',
  `rule_version` VARCHAR(40) NOT NULL COMMENT '对账规则版本',
  `difference_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '差异条数',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_reconciliation_1` (`merchant_id`, `business_date`, `file_hash`),
  KEY `ix_sim_reconciliation_1` (`status`, `business_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_recon_difference` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `reconciliation_id` BIGINT UNSIGNED NOT NULL COMMENT '对账批次',
  `difference_type` ENUM('MISSING_LOCAL','MISSING_CHANNEL','AMOUNT','DUPLICATE','LATE','SCOPE') NOT NULL COMMENT '差异类型',
  `reference` VARCHAR(80) NOT NULL COMMENT '业务号',
  `severity` ENUM('S0','S1','S2') NOT NULL COMMENT '严重度',
  `status` ENUM('OPEN','ASSIGNED','RESOLVED','CLOSED') NOT NULL COMMENT '状态',
  `assignee_id` BIGINT UNSIGNED NULL COMMENT '处理人',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '独立关闭人',
  `due_at` DATETIME(3) NOT NULL COMMENT '处理期限',
  `resolution` VARCHAR(1000) NULL COMMENT '证据与处理单号',
  PRIMARY KEY (`id`),
  KEY `ix_sim_recon_difference_1` (`status`, `due_at`),
  CONSTRAINT `ck_sim_recon_difference_1` CHECK (checker_id IS NULL OR assignee_id IS NULL OR checker_id <> assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_export` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `requester_id` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `scope_key` VARCHAR(100) NOT NULL COMMENT '服务端授权范围',
  `filters_json` JSON NOT NULL COMMENT '允许的过滤字段',
  `status` ENUM('REQUESTED','APPROVED','RUNNING','READY','REJECTED','EXPIRED') NOT NULL COMMENT '状态',
  `file_id` BIGINT UNSIGNED NULL COMMENT '水印后私有文件',
  `expires_at` DATETIME(3) NULL COMMENT '下载到期',
  PRIMARY KEY (`id`),
  KEY `ix_sim_export_1` (`requester_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_notification` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT '收件用户',
  `event_key` VARCHAR(100) NOT NULL COMMENT '业务事件',
  `template_version` VARCHAR(40) NOT NULL COMMENT '模板版本',
  `payload` JSON NOT NULL COMMENT '最少展示字段',
  `read_at` DATETIME(3) NULL COMMENT '已读',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_notification_1` (`user_id`, `event_key`, `template_version`),
  KEY `ix_sim_notification_1` (`user_id`, `created_at`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_admin_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `request_type` ENUM('SCOPE_GRANT','PRIVACY') NOT NULL COMMENT '工单类型',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '独立处理人',
  `request_payload` JSON NOT NULL COMMENT '按类型校验，不含证件原文',
  `status` ENUM('REQUESTED','APPROVED','REJECTED','DONE') NOT NULL COMMENT '状态',
  `resolution` VARCHAR(1000) NULL COMMENT '处理依据',
  PRIMARY KEY (`id`),
  KEY `ix_sim_admin_request_1` (`request_type`, `status`, `created_at`),
  CONSTRAINT `ck_sim_admin_request_1` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_admin_request_2` CHECK (status = 'REQUESTED' OR checker_id IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_audit` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `actor_id` BIGINT UNSIGNED NULL COMMENT '用户或服务主体',
  `action` VARCHAR(80) NOT NULL COMMENT '动作',
  `resource_type` VARCHAR(40) NOT NULL COMMENT '对象类',
  `resource_id` VARCHAR(64) NOT NULL COMMENT '对象ID',
  `request_id` CHAR(36) NOT NULL COMMENT '串联请求',
  `reason` VARCHAR(500) NULL COMMENT '用途',
  `redacted_detail` JSON NOT NULL COMMENT '前后摘要与决定，不含敏感原文',
  PRIMARY KEY (`id`),
  KEY `ix_sim_audit_1` (`request_id`),
  KEY `ix_sim_audit_2` (`resource_type`, `resource_id`),
  KEY `ix_sim_audit_3` (`actor_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_business_day` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `book_id` BIGINT UNSIGNED NOT NULL COMMENT '所属模拟账簿',
  `business_date` DATE NOT NULL COMMENT 'Asia/Shanghai业务日',
  `status` ENUM('OPEN','CLOSING','CLOSED','REOPENED_REVIEW') NOT NULL COMMENT '已关闭后只允许复核性重开',
  `cutoff_at` DATETIME(3) NOT NULL COMMENT '本业务日结束的UTC时刻',
  `high_watermark` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '关账快照采用的最大账簿序号',
  `report_revision` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '报告修订号',
  `snapshot_hash` CHAR(64) NULL COMMENT '不可变报告摘要',
  `closed_at` DATETIME(3) NULL COMMENT '首次完成关账时刻',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_business_day_1` (`book_id`, `business_date`),
  UNIQUE KEY `uq_sim_business_day_2` (`id`, `book_id`, `business_date`),
  KEY `ix_sim_business_day_1` (`book_id`, `status`, `business_date`),
  CONSTRAINT `ck_sim_business_day_1` CHECK (report_revision > 0),
  CONSTRAINT `ck_sim_business_day_2` CHECK (status NOT IN ('CLOSED','REOPENED_REVIEW') OR (snapshot_hash IS NOT NULL AND closed_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `sim_business_day_request` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '内部键；API用十进制字符串',
  `environment` ENUM('SIMULATION') NOT NULL DEFAULT 'SIMULATION' COMMENT '仅模拟；不可由请求切换真实',
  `created_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `updated_at` DATETIME(3) NOT NULL COMMENT 'UTC，服务端生成',
  `version` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT '对象版本；不可变事件固定为1',
  `day_id` BIGINT UNSIGNED NOT NULL COMMENT '关联业务日',
  `action` ENUM('CLOSE','REOPEN_REVIEW','RECLOSE') NOT NULL COMMENT '请求动作',
  `expected_day_version` INT UNSIGNED NOT NULL COMMENT '批准和执行时都检查',
  `maker_id` BIGINT UNSIGNED NOT NULL COMMENT '发起主体',
  `checker_id` BIGINT UNSIGNED NULL COMMENT '复核主体',
  `status` ENUM('REQUESTED','APPROVED','REJECTED','EXECUTING','EXECUTED','FAILED','EXPIRED') NOT NULL COMMENT '请求生命周期',
  `reason` VARCHAR(1000) NOT NULL COMMENT '必须说明原因',
  `evidence_refs` JSON NOT NULL COMMENT '证据ID列表',
  `reconciliation_refs` JSON NOT NULL COMMENT '有范围核验的对账批次ID列表',
  `impact_summary` VARCHAR(2000) NOT NULL COMMENT '跨日差异/影响范围',
  `expires_at` DATETIME(3) NOT NULL COMMENT '最多申请后24小时的候选参数',
  `execution_key` VARCHAR(100) NOT NULL COMMENT 'dayId+action+expectedVersion+requestId唯一',
  `result_summary` JSON NULL COMMENT '执行结果/新旧报告哈希，不存支付秘密',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_sim_business_day_request_1` (`execution_key`),
  KEY `ix_sim_business_day_request_1` (`day_id`, `status`),
  KEY `ix_sim_business_day_request_2` (`maker_id`, `created_at`),
  CONSTRAINT `ck_sim_business_day_request_1` CHECK (checker_id IS NULL OR checker_id <> maker_id),
  CONSTRAINT `ck_sim_business_day_request_2` CHECK (status NOT IN ('APPROVED','REJECTED','EXECUTING','EXECUTED','FAILED') OR checker_id IS NOT NULL),
  CONSTRAINT `ck_sim_business_day_request_3` CHECK (expected_day_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- FKs are added only after every referenced table exists.
ALTER TABLE `sim_identity` ADD CONSTRAINT `fk_sim_identity_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_session` ADD CONSTRAINT `fk_sim_session_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_consent` ADD CONSTRAINT `fk_sim_consent_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_merchant` ADD CONSTRAINT `fk_sim_merchant_1` FOREIGN KEY (`owner_user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_merchant_application` ADD CONSTRAINT `fk_sim_merchant_application_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_merchant_application` ADD CONSTRAINT `fk_sim_merchant_application_2` FOREIGN KEY (`applicant_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_merchant_application` ADD CONSTRAINT `fk_sim_merchant_application_3` FOREIGN KEY (`reviewer_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_store` ADD CONSTRAINT `fk_sim_store_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_role_assignment` ADD CONSTRAINT `fk_sim_role_assignment_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_product` ADD CONSTRAINT `fk_sim_product_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_product` ADD CONSTRAINT `fk_sim_product_2` FOREIGN KEY (`store_id`, `merchant_id`) REFERENCES `sim_store` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_rule_version` ADD CONSTRAINT `fk_sim_rule_version_1` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_rule_version` ADD CONSTRAINT `fk_sim_rule_version_2` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order` ADD CONSTRAINT `fk_sim_order_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order` ADD CONSTRAINT `fk_sim_order_2` FOREIGN KEY (`store_id`, `merchant_id`) REFERENCES `sim_store` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order` ADD CONSTRAINT `fk_sim_order_3` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order` ADD CONSTRAINT `fk_sim_order_4` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order` ADD CONSTRAINT `fk_sim_order_5` FOREIGN KEY (`rule_id`) REFERENCES `sim_rule_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order_item` ADD CONSTRAINT `fk_sim_order_item_1` FOREIGN KEY (`order_id`) REFERENCES `sim_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_order_item` ADD CONSTRAINT `fk_sim_order_item_2` FOREIGN KEY (`product_id`) REFERENCES `sim_product` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_credential` ADD CONSTRAINT `fk_sim_credential_1` FOREIGN KEY (`order_id`) REFERENCES `sim_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_credential` ADD CONSTRAINT `fk_sim_credential_2` FOREIGN KEY (`bound_user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_limit_account` ADD CONSTRAINT `fk_sim_limit_account_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_limit_account` ADD CONSTRAINT `fk_sim_limit_account_2` FOREIGN KEY (`rule_id`) REFERENCES `sim_rule_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_reservation` ADD CONSTRAINT `fk_sim_reservation_1` FOREIGN KEY (`order_id`) REFERENCES `sim_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_reservation` ADD CONSTRAINT `fk_sim_reservation_2` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_receivable` ADD CONSTRAINT `fk_sim_receivable_1` FOREIGN KEY (`order_id`, `merchant_id`) REFERENCES `sim_order` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_receivable` ADD CONSTRAINT `fk_sim_receivable_2` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_repayment` ADD CONSTRAINT `fk_sim_repayment_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_repayment` ADD CONSTRAINT `fk_sim_repayment_2` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_repayment_allocation` ADD CONSTRAINT `fk_sim_repayment_allocation_1` FOREIGN KEY (`repayment_id`, `merchant_id`) REFERENCES `sim_repayment` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_repayment_allocation` ADD CONSTRAINT `fk_sim_repayment_allocation_2` FOREIGN KEY (`receivable_id`, `merchant_id`) REFERENCES `sim_receivable` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_offline_review` ADD CONSTRAINT `fk_sim_offline_review_1` FOREIGN KEY (`repayment_id`) REFERENCES `sim_repayment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_offline_review` ADD CONSTRAINT `fk_sim_offline_review_2` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_offline_review` ADD CONSTRAINT `fk_sim_offline_review_3` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_refund` ADD CONSTRAINT `fk_sim_refund_1` FOREIGN KEY (`order_id`, `merchant_id`) REFERENCES `sim_order` (`id`, `merchant_id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_refund` ADD CONSTRAINT `fk_sim_refund_2` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_refund` ADD CONSTRAINT `fk_sim_refund_3` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_dispute` ADD CONSTRAINT `fk_sim_dispute_1` FOREIGN KEY (`order_id`) REFERENCES `sim_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_dispute` ADD CONSTRAINT `fk_sim_dispute_2` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_dispute` ADD CONSTRAINT `fk_sim_dispute_3` FOREIGN KEY (`assignee_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_book` ADD CONSTRAINT `fk_sim_book_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal` ADD CONSTRAINT `fk_sim_journal_1` FOREIGN KEY (`book_id`) REFERENCES `sim_book` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal` ADD CONSTRAINT `fk_sim_journal_2` FOREIGN KEY (`reversal_of`) REFERENCES `sim_journal` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal` ADD CONSTRAINT `fk_sim_journal_3` FOREIGN KEY (`business_day_id`, `book_id`, `posting_date`) REFERENCES `sim_business_day` (`id`, `book_id`, `business_date`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal_line` ADD CONSTRAINT `fk_sim_journal_line_1` FOREIGN KEY (`journal_id`) REFERENCES `sim_journal` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal_line` ADD CONSTRAINT `fk_sim_journal_line_2` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_journal_line` ADD CONSTRAINT `fk_sim_journal_line_3` FOREIGN KEY (`order_id`) REFERENCES `sim_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_adjustment` ADD CONSTRAINT `fk_sim_adjustment_1` FOREIGN KEY (`original_journal_id`) REFERENCES `sim_journal` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_adjustment` ADD CONSTRAINT `fk_sim_adjustment_2` FOREIGN KEY (`receivable_id`) REFERENCES `sim_receivable` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_adjustment` ADD CONSTRAINT `fk_sim_adjustment_3` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_adjustment` ADD CONSTRAINT `fk_sim_adjustment_4` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_file` ADD CONSTRAINT `fk_sim_file_1` FOREIGN KEY (`owner_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_reconciliation` ADD CONSTRAINT `fk_sim_reconciliation_1` FOREIGN KEY (`merchant_id`) REFERENCES `sim_merchant` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_reconciliation` ADD CONSTRAINT `fk_sim_reconciliation_2` FOREIGN KEY (`file_id`) REFERENCES `sim_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_recon_difference` ADD CONSTRAINT `fk_sim_recon_difference_1` FOREIGN KEY (`reconciliation_id`) REFERENCES `sim_reconciliation` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_recon_difference` ADD CONSTRAINT `fk_sim_recon_difference_2` FOREIGN KEY (`assignee_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_recon_difference` ADD CONSTRAINT `fk_sim_recon_difference_3` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_export` ADD CONSTRAINT `fk_sim_export_1` FOREIGN KEY (`requester_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_export` ADD CONSTRAINT `fk_sim_export_2` FOREIGN KEY (`file_id`) REFERENCES `sim_file` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_notification` ADD CONSTRAINT `fk_sim_notification_1` FOREIGN KEY (`user_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_admin_request` ADD CONSTRAINT `fk_sim_admin_request_1` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_admin_request` ADD CONSTRAINT `fk_sim_admin_request_2` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_audit` ADD CONSTRAINT `fk_sim_audit_1` FOREIGN KEY (`actor_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_business_day` ADD CONSTRAINT `fk_sim_business_day_1` FOREIGN KEY (`book_id`) REFERENCES `sim_book` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_business_day_request` ADD CONSTRAINT `fk_sim_business_day_request_1` FOREIGN KEY (`day_id`) REFERENCES `sim_business_day` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_business_day_request` ADD CONSTRAINT `fk_sim_business_day_request_2` FOREIGN KEY (`maker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `sim_business_day_request` ADD CONSTRAINT `fk_sim_business_day_request_3` FOREIGN KEY (`checker_id`) REFERENCES `sim_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

-- Application invariants: balance across journal lines, authorization and close/post locks
-- cannot be enforced by row CHECK alone. See chapters 3/4 and acceptance tests.
