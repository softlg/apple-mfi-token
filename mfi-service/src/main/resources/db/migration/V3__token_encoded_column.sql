-- P5 流水线：t_token 需要保存苹果下发的 token 原文。
--
-- 苹果的 xlsx 给四列：PPID / Token ID / Base64-encoded Token / CRC32 in HEX。基线与 MES 的
-- t_token_detail 只留了 Token ID 与 CRC32（MES 把明细表的 secret_key 直接填成 crc，
-- 见 TokenInitDetailListener:99），原文存在它自己的 t_token_download_content.encode_token 里。
-- 本仓库把 detail 与 download_content 合并成一张 t_token，所以必须补出这一列，否则合并后
-- 原文没有落点，等于每次下载都丢掉一份苹果已经下发的材料。
ALTER TABLE t_token
    ADD COLUMN encoded_token MEDIUMTEXT DEFAULT NULL COMMENT '苹果下发的 Base64 token 原文，敏感，禁止进日志与导出列' AFTER secret_key;
