-- PostgreSQL 无法原地把新增列移动到首位，因此先保留旧表，再按统一规范创建新表。
alter table conversation_session_user_state
    rename to __conversation_session_user_state_without_identity;

create table conversation_session_user_state (
    id bigint generated always as identity,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    conversation_session_id varchar(64) not null,
    last_read_event_sequence bigint not null default 0,
    sort_order bigint not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    version bigint not null default 0
);

comment on column conversation_session_user_state.id is
    '数据库自增主键，仅用于持久化层内部定位用户会话状态记录';

-- 按稳定业务键复制历史状态，数据库为每行生成新的代理主键。
insert into conversation_session_user_state(
    tenant_id,
    user_id,
    conversation_session_id,
    last_read_event_sequence,
    sort_order,
    created_at,
    updated_at,
    version)
select tenant_id,
       user_id,
       conversation_session_id,
       last_read_event_sequence,
       sort_order,
       created_at,
       updated_at,
       version
  from __conversation_session_user_state_without_identity
 order by tenant_id, user_id, conversation_session_id;

drop table __conversation_session_user_state_without_identity;

-- id 统一承担主键职责，原自然主键继续作为业务唯一约束保护一人一会话一条状态。
alter table conversation_session_user_state
    add constraint conversation_session_user_state_pkey primary key (id),
    add constraint uq_conversation_session_user_state_identity
        unique (tenant_id, user_id, conversation_session_id),
    add constraint uq_conversation_session_user_sort
        unique (tenant_id, user_id, sort_order) deferrable initially deferred,
    add constraint fk_conversation_session_user_state_user
        foreign key (tenant_id, user_id) references user_account(tenant_id, user_id)
        on delete cascade,
    add constraint fk_conversation_session_user_state_session
        foreign key (tenant_id, conversation_session_id)
        references conversation_session(tenant_id, conversation_session_id)
        on delete cascade,
    add constraint ck_conversation_session_user_read_sequence
        check (last_read_event_sequence >= 0);

-- 恢复会话排序、未读判断使用的查询索引。
create index ix_conversation_session_user_order
    on conversation_session_user_state(tenant_id, user_id, sort_order);

create index ix_conversation_session_user_unread
    on conversation_session_user_state(
        tenant_id, user_id, last_read_event_sequence, conversation_session_id);
