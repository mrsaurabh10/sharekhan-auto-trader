package org.com.sharekhan.migration;

import org.com.sharekhan.entity.StrategySubscriptionEntity;
import org.com.sharekhan.entity.TriggerTradeRequestEntity;
import org.com.sharekhan.entity.TriggeredTradeSetupEntity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.sql.Types;

@Repository
@ConditionalOnProperty(prefix="app.trading-state.postgres",name="enabled",havingValue="true")
public class PostgresTradingStateStore {
 private final NamedParameterJdbcTemplate jdbc;
 public PostgresTradingStateStore(@Qualifier("auditPostgresJdbcTemplate") NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}
 public void copyStrategies(List<StrategySubscriptionEntity> rows){batch("strategy_subscriptions","id,template_id,symbol,lots,intraday,app_user_id,broker_credentials_id,source,status,last_message,last_evaluation_status,generated_trade_request_id,created_at,updated_at,last_evaluated_at,completed_at",":id,:templateId,:symbol,:lots,:intraday,:appUserId,:brokerCredentialsId,:source,:status,:lastMessage,:lastEvaluationStatus,:generatedTradeRequestId,:createdAt,:updatedAt,:lastEvaluatedAt,:completedAt",rows);}
 public void copyRequests(List<TriggerTradeRequestEntity> rows){batch("trigger_trade_requests","id,symbol,scrip_code,exchange,instrument_type,strike_price,option_type,expiry,quantity,lots,broker_credentials_id,app_user_id,entry_price,stop_loss,target1,target2,target3,trailing_sl,tsl_enabled,use_spot_price,use_spot_for_entry,use_spot_for_sl,use_spot_for_target,spot_scrip_code,intraday,source,opening_rule_reset,gap_policy_initialized,gap_protection_enabled,gap_day_open,gap_previous_close,gap_stop_loss,gap_reentry_count,status,reason,comment,created_at",":id,:symbol,:scripCode,:exchange,:instrumentType,:strikePrice,:optionType,:expiry,:quantity,:lots,:brokerCredentialsId,:appUserId,:entryPrice,:stopLoss,:target1,:target2,:target3,:trailingSl,:tslEnabled,:useSpotPrice,:useSpotForEntry,:useSpotForSl,:useSpotForTarget,:spotScripCode,:intraday,:source,:openingRuleReset,:gapPolicyInitialized,:gapProtectionEnabled,:gapDayOpen,:gapPreviousClose,:gapStopLoss,:gapReentryCount,:status,:reason,:comment,:createdAt",rows);}
 public void copySetups(List<TriggeredTradeSetupEntity> rows){batch("triggered_trade_setups","id,trigger_request_id,symbol,scrip_code,broker_credentials_id,app_user_id,exchange,instrument_type,strike_price,option_type,expiry,quantity,lots,original_lots,target_order_group_id,target_stage,entry_price,actual_entry_price,stop_loss,target1,target2,target3,trailing_sl,tsl_enabled,use_spot_price,use_spot_for_entry,use_spot_for_sl,use_spot_for_target,spot_scrip_code,order_id,exit_order_id,exit_reason,reason,comment,intraday,source,gap_protection_enabled,gap_day_open,gap_previous_close,gap_stop_loss,gap_reentry_count,status,triggered_at,entry_at,exited_at,exit_price,pnl,trade_cost,effective_pnl,exit_order_placed_at,exit_claimed_at",":id,:triggerRequestId,:symbol,:scripCode,:brokerCredentialsId,:appUserId,:exchange,:instrumentType,:strikePrice,:optionType,:expiry,:quantity,:lots,:originalLots,:targetOrderGroupId,:targetStage,:entryPrice,:actualEntryPrice,:stopLoss,:target1,:target2,:target3,:trailingSl,:tslEnabled,:useSpotPrice,:useSpotForEntry,:useSpotForSl,:useSpotForTarget,:spotScripCode,:orderId,:exitOrderId,:exitReason,:reason,:comment,:intraday,:source,:gapProtectionEnabled,:gapDayOpen,:gapPreviousClose,:gapStopLoss,:gapReentryCount,:status,:triggeredAt,:entryAt,:exitedAt,:exitPrice,:pnl,:tradeCost,:effectivePnl,:exitOrderPlacedAt,:exitClaimedAt",rows);}
 private <T> void batch(String table,String columns,String values,List<T> rows){if(rows.isEmpty())return;jdbc.batchUpdate("INSERT INTO "+table+" ("+columns+") OVERRIDING SYSTEM VALUE VALUES ("+values+") ON CONFLICT (id) DO NOTHING",rows.stream().map(this::parameters).toArray(SqlParameterSource[]::new));}
 private BeanPropertySqlParameterSource parameters(Object row){BeanPropertySqlParameterSource source=new BeanPropertySqlParameterSource(row);source.registerSqlType("status", Types.VARCHAR);return source;}
 public long count(String table){Long n=jdbc.getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM "+table,Long.class);return n==null?0:n;}
 public void sync(){for(String t:List.of("strategy_subscriptions","trigger_trade_requests","triggered_trade_setups"))jdbc.getJdbcTemplate().execute("SELECT setval(pg_get_serial_sequence('"+t+"','id'),COALESCE((SELECT MAX(id) FROM "+t+"),1),true)");}
}
