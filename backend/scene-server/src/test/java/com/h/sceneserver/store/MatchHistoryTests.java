package com.h.sceneserver.store;

import com.h.gameprotocol.persistence.*;
import com.h.gameprotocol.proto.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mockito.AdditionalAnswers;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** H2 验证真实 MyBatis-Plus 映射与 Spring 事务；不是实际 MySQL 或 Nacos 联调。 */
class MatchHistoryTests {
    @Configuration @EnableTransactionManagement
    static class Config {
        @Bean DataSource source() {return new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");}
        @Bean JdbcTemplate jdbc(DataSource source) {return new JdbcTemplate(source);}
        @Bean PlatformTransactionManager transactions(DataSource source) {return new DataSourceTransactionManager(source);}
        @Bean SqlSessionFactory sessions(DataSource source) throws Exception {
            var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);
            var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);
            factory.setGlobalConfig(new GlobalConfig().setBanner(false));
            var sessions=factory.getObject();sessions.getConfiguration().addMapper(MatchMapper.class);
            sessions.getConfiguration().addMapper(MatchResultMapper.class);return sessions;
        }
        @Bean MatchMapper matches(SqlSessionFactory sessions) {
            return new SqlSessionTemplate(sessions).getMapper(MatchMapper.class);
        }
        @Bean MatchResultMapper results(SqlSessionFactory sessions) {
            // 默认委托真实 Mapper；只有回滚测试针对第二次插入注入故障。
            MatchResultMapper real=new SqlSessionTemplate(sessions).getMapper(MatchResultMapper.class);
            return mock(MatchResultMapper.class,AdditionalAnswers.delegatesTo(real));
        }
        @Bean MatchHistoryRepository repository(MatchMapper matches,MatchResultMapper results) {return new MatchHistoryRepository(matches,results);}
    }
    AnnotationConfigApplicationContext context;
    JdbcTemplate jdbc;MatchHistoryRepository repository;
    @BeforeEach void open() {
        context=new AnnotationConfigApplicationContext(Config.class);jdbc=context.getBean(JdbcTemplate.class);
        jdbc.execute("CREATE TABLE game_match(match_id VARCHAR(288) PRIMARY KEY,data BLOB NOT NULL)");
        jdbc.execute("CREATE TABLE game_match_result(result_id VARCHAR(384) PRIMARY KEY,data BLOB NOT NULL)");
        repository=context.getBean(MatchHistoryRepository.class);
    }
    @AfterEach void close() {context.close();}
    static MatchRanking ranking(long id,String... users) {
        var r=MatchRanking.newBuilder().setSceneId(id).setSceneInstanceId("scene01").setStartedAtMs(1000)
                .setEndedAtMs(601000).setFrozen(true).setEndReason(MatchRanking.EndReason.TIME_LIMIT);
        for(int i=0;i<users.length;i++)r.addPlayers(MatchRankingEntry.newBuilder().setUserId(users[i]).setNickname("昵称"+i).setRank(i+1).setArea(100-i));
        return r.build();
    }
    @Test void savesTwoColumnProtobufAndRepeatedFinishDoesNotDuplicateOrChangeHistory() throws Exception {
        var finalRank=ranking(123,"a@example.com","b@example.com");
        repository.started(finalRank.toBuilder().setFrozen(false).setEndedAtMs(0).setEndReason(MatchRanking.EndReason.NONE).build());
        repository.finish(finalRank);repository.finish(finalRank);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM game_match",Integer.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM game_match_result",Integer.class));
        MatchProto match=MatchProto.parseFrom(jdbc.queryForObject("SELECT data FROM game_match",byte[].class));
        assertEquals(2,match.getFinalPlayerCount());assertEquals("昵称0",match.getPlayers(0).getNickname());
        var page=repository.history("a@example.com",20,"");
        assertEquals(1,page.records().size());assertEquals("scene01:123",page.records().get(0).getMatchId());
        assertEquals(2,page.records().get(0).getFinalPlayerCount());assertEquals("",page.nextCursor());
        assertThrows(IllegalStateException.class,()->repository.finish(finalRank.toBuilder().setEndedAtMs(601001).build()));
    }
    @Test void rollbackIncludesMatchAndAlreadyInsertedPlayersIfLaterPlayerWriteFails() {
        var results=context.getBean(MatchResultMapper.class);
        doThrow(new IllegalStateException("模拟第二条写入失败")).when(results)
                .insert(argThat((MatchResultEntity e)->e!=null && e.getResultId().contains("b@example.com")));
        assertThrows(IllegalStateException.class,()->repository.finish(ranking(123,"a@example.com","b@example.com")));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_match",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_match_result",Integer.class));
    }
    @Test void refusesLeftPlayersBeforeWritingAnyRows() {
        var r=ranking(123,"a@example.com");
        assertThrows(IllegalArgumentException.class,()->repository.finish(r.toBuilder().setPlayers(0,r.getPlayers(0).toBuilder().setLeft(true)).build()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_match",Integer.class));
    }
    @Test void allLeftStoresEmptyMatchWithoutPersonalRecords() throws Exception {
        repository.finish(ranking(123).toBuilder().setEndReason(MatchRanking.EndReason.ALL_LEFT).build());
        var match=MatchProto.parseFrom(jdbc.queryForObject("SELECT data FROM game_match",byte[].class));
        assertEquals(0,match.getFinalPlayerCount());assertEquals(0,match.getPlayersCount());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM game_match_result",Integer.class));
    }
    @Test void rangeAndCursorStayWithinCurrentUserIncludingPercentAndUnderscore() {
        for(long id=1;id<4;id++)repository.finish(ranking(id,"a_%@example.com","a@other.com"));
        var first=repository.history("a_%@example.com",2,"");
        assertEquals(2,first.records().size());assertFalse(first.nextCursor().isEmpty());
        var second=repository.history("a_%@example.com",2,first.nextCursor());
        assertEquals(1,second.records().size());assertEquals("",second.nextCursor());
        assertThrows(IllegalArgumentException.class,()->repository.history("a_%@example.com",20,MatchRecordKeys.result("a@other.com","scene01:1")));
        assertTrue(first.records().stream().allMatch(r->r.getUserId().equals("a_%@example.com")));
    }
}
