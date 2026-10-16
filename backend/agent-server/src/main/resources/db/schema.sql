-- 球球项目统一建表脚本：只保存账号和战绩，每张表只有“唯一标识 + data”两列。
-- 请先在 MySQL 客户端选择数据库。应用不会自动执行本脚本。
-- 本脚本不创建数据库、不删除旧表、不修改已有表、不插入示例账号。
-- CREATE TABLE IF NOT EXISTS 不会把已有的旧结构改成下面的新结构。
-- Agent 已移除 game_player/game_player_actor 的代码依赖，不需要为它创建 MySQL 表。

-- 1. Login：一名注册用户一行。
-- user_id 就是注册邮箱，不自增；data 是 UserProto.toByteArray() 的原始字节。
-- UserProto 保存邮箱、BCrypt 密码哈希、禁用状态、创建时间、最近登录时间。
-- 不存 Actor、Agent、Gateway、场景绑定、请求编号或战绩。
CREATE TABLE IF NOT EXISTS game_account (
    user_id VARCHAR(64) NOT NULL COMMENT '注册邮箱账号，不自增',
    data BLOB NOT NULL COMMENT 'UserProto 序列化字节，密码只存 BCrypt 哈希',
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='注册账号';

-- 2. Scene：一场比赛一行。
-- match_id 固定使用已有的 Scene 实例 ID + ':' + Scene ID（无符号十进制），例如 scene01:123。
-- 组合时不要丢掉实例 ID：不同实例不能只靠各自的 Scene ID 判断是同一局。
-- data 是 MatchProto.toByteArray()，包含开赛时间、实际结束时间、结束原因，以及整局最终排名。
-- 最终排名只包含结束瞬间仍在场的玩家，包括仍在场的观战玩家；已离场的不写入。
-- 结束人数必须等于最终排名列表人数。全员离场时保存该局，但列表为空、结束人数为 0。
-- 进行中的比赛可以先保存开始记录；未结束时 end_time=0、end_reason=NOT_ENDED，没有最终排名。
CREATE TABLE IF NOT EXISTS game_match (
    match_id VARCHAR(288) NOT NULL COMMENT 'Scene 实例 ID 与 Scene ID 组成的固定比赛标识',
    data MEDIUMBLOB NOT NULL COMMENT 'MatchProto 序列化字节，含比赛信息和最终排名列表',
    PRIMARY KEY (match_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='比赛记录';

-- 3. Scene：一场比赛中，每名结束瞬间仍在场的玩家一行。
-- result_id 固定使用 '<userId字符数>:<userId>:<match_id>'，例如 13:a@example.com:scene01:123。
-- 它只用于定位“这个用户的这一局战绩”，不是新的请求编号，不随机生成。
-- 同一用户同一比赛的主键相同，因此重复插入不能生成第二条战绩。
-- data 是 MatchResultProto.toByteArray()，包含用户 ID、比赛标识、本局昵称、最终人数和名次等。
-- 用户下一局换昵称，不修改以前的战绩。提前退出/掉线且结束时已不在场的玩家，不插入这一行。
-- 个人战绩重复保存本局时间和人数，便于一次查询就取得展示内容，不需要在 SQL 中解析或关联 BLOB。
CREATE TABLE IF NOT EXISTS game_match_result (
    result_id VARCHAR(384) NOT NULL COMMENT '用户 ID 与比赛标识组成的固定战绩标识',
    data BLOB NOT NULL COMMENT 'MatchResultProto 序列化字节，保存这个用户这一局的战绩',
    PRIMARY KEY (result_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='个人战绩';

-- 按用户查询：数据库不能直接筛选 Protobuf 字节里的 user_id，改为查主键前缀范围。
-- 例如 a@example.com：下界参数为 '13:a@example.com:'，上界参数为 '13:a@example.com;'。
-- ':' 与 ';' 是相邻的 ASCII 字符，配合 utf8mb4_bin 可定位该用户的全部战绩。
-- 参数必须绑定，不把客户端账号拼接进 SQL 文本；页码/数量也使用绑定参数。
-- SELECT result_id, data FROM game_match_result
-- WHERE result_id >= ? AND result_id < ? ORDER BY result_id LIMIT ?, ?;
-- 上面按主键分页，不保证按比赛时间排序；若需要数据库直接按时间排序，应重新讨论两列限制。

-- 以下要求属于存取代码，不是建表后自动具备的功能：
-- 1) 写入使用 proto.toByteArray()，读取使用对应 Proto.parseFrom(data)，不存 JSON/Base64。
-- 2) 核对主键与 Protobuf 内的用户/比赛标识一致；校验排名、人数和时间，禁止写入不合法记录。
-- 3) 比赛最终记录和所有个人战绩必须在同一个 MySQL 事务提交。
-- 4) 最终结果不可变；主键冲突时核对已有内容，相同才算原保存成功，不能覆盖成另一份结果。
-- Scene 已通过 MyBatis-Plus 的 MatchHistoryRepository 实现以上读写；须先建表并配置数据源才能运行。
-- Agent 和 AgentMgr 按当前方案不需要 MySQL 表。不要给 Context 或请求去重另建用户资料表。
