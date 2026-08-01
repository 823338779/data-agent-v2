-- 会话保存跨用户共享的活动时间和可通知事件水位，旧会话按更新时间确定性回填。
alter table conversation_session
    add column last_activity_at timestamptz,
    add column last_notifiable_event_sequence bigint not null default 0;

update conversation_session
   set last_activity_at = updated_at;

alter table conversation_session
    alter column last_activity_at set default current_timestamp,
    alter column last_activity_at set not null,
    add constraint ck_conversation_session_notifiable_sequence
        check (last_notifiable_event_sequence >= 0);

-- 用户状态独立保存已读水位和排序，复合主键及外键同时封闭租户边界。
create table conversation_session_user_state (
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    last_read_event_sequence bigint not null default 0,
    sort_order bigint not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint pk_conversation_session_user_state
        primary key (tenant_id, user_id, conversation_session_id),
    constraint uq_conversation_session_user_sort
        unique (tenant_id, user_id, sort_order) deferrable initially deferred,
    constraint fk_conversation_session_user_state_user
        foreign key (tenant_id, user_id) references user_account(tenant_id, user_id)
        on delete cascade,
    constraint fk_conversation_session_user_state_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id)
        on delete cascade,
    constraint ck_conversation_session_user_read_sequence
        check (last_read_event_sequence >= 0)
);

-- 每个用户的 ordering_version 为跨设备完整重排提供独立乐观锁。
create table conversation_preference (
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    ordering_version bigint not null default 0,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0,
    constraint pk_conversation_preference primary key (tenant_id, user_id),
    constraint fk_conversation_preference_user
        foreign key (tenant_id, user_id) references user_account(tenant_id, user_id)
        on delete cascade,
    constraint ck_conversation_preference_ordering_version check (ordering_version >= 0)
);

-- 现有用户先获得偏好记录，再按同租户可见会话的活动时间生成连续且确定的顺序。
insert into conversation_preference(tenant_id, user_id)
select tenant_id, user_id
  from user_account
 where is_deleted = false;

insert into conversation_session_user_state(
    tenant_id, user_id, conversation_session_id, last_read_event_sequence, sort_order)
select users.tenant_id,
       users.user_id,
       sessions.conversation_session_id,
       0,
       row_number() over (
           partition by users.tenant_id, users.user_id
           order by sessions.last_activity_at desc, sessions.conversation_session_id desc
       ) - 1
  from user_account users
  join conversation_session sessions on sessions.tenant_id = users.tenant_id
 where users.is_deleted = false
   and sessions.is_deleted = false;

-- 三类索引分别服务用户顺序读取、未读比较及实例级 activity reconciliation。
create index ix_conversation_session_user_order
    on conversation_session_user_state(tenant_id, user_id, sort_order);

create index ix_conversation_session_user_unread
    on conversation_session_user_state(
        tenant_id, user_id, last_read_event_sequence, conversation_session_id);

create index ix_conversation_session_activity_reconciliation
    on conversation_session(tenant_id, last_activity_at, conversation_session_id)
    where is_deleted = false;
