create table tenant (
    id varchar(64) primary key,
    name varchar(128) not null,
    status varchar(32) not null default 'active',
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint ck_tenant_status check (status in ('active', 'disabled')),
    constraint ck_tenant_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table user_account (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    username varchar(128) not null,
    password_hash varchar(100) not null,
    status varchar(32) not null default 'active',
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_user_account_tenant_id unique (tenant_id, id),
    constraint uq_user_account_username unique (tenant_id, username),
    constraint fk_user_account_tenant foreign key (tenant_id) references tenant(id),
    constraint ck_user_account_status check (status in ('active', 'disabled')),
    constraint ck_user_account_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table tenant_membership (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    role varchar(32) not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_tenant_membership_tenant_id unique (tenant_id, id),
    constraint uq_tenant_membership_role unique (tenant_id, user_id, role),
    constraint fk_tenant_membership_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_tenant_membership_user foreign key (tenant_id, user_id)
        references user_account(tenant_id, id),
    constraint ck_tenant_membership_role check (role in ('ADMIN', 'USER')),
    constraint ck_tenant_membership_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table login_session (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    user_id varchar(64) not null,
    token_hash char(64) not null,
    expires_at timestamptz not null,
    revoked_at timestamptz,
    last_seen_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_login_session_tenant_id unique (tenant_id, id),
    constraint uq_login_session_token_hash unique (token_hash),
    constraint fk_login_session_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_login_session_user foreign key (tenant_id, user_id)
        references user_account(tenant_id, id),
    constraint ck_login_session_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table agent_session (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    title varchar(256) not null,
    tag varchar(128),
    session_type varchar(32) not null,
    created_by varchar(64) not null,
    message_sequence bigint not null default 0,
    event_sequence bigint not null default 0,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_session_tenant_id unique (tenant_id, id),
    constraint fk_agent_session_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_agent_session_creator foreign key (tenant_id, created_by)
        references user_account(tenant_id, id),
    constraint ck_agent_session_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table agent_message (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    session_id varchar(64) not null,
    run_id varchar(64),
    role varchar(16) not null,
    content text not null,
    sequence bigint not null,
    author_user_id varchar(64),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_message_tenant_id unique (tenant_id, id),
    constraint uq_agent_message_sequence unique (tenant_id, session_id, sequence),
    constraint fk_agent_message_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_agent_message_session foreign key (tenant_id, session_id)
        references agent_session(tenant_id, id),
    constraint fk_agent_message_author foreign key (tenant_id, author_user_id)
        references user_account(tenant_id, id),
    constraint ck_agent_message_role check (role in ('user', 'assistant', 'system')),
    constraint ck_agent_message_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table agent_run (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    session_id varchar(64) not null,
    triggering_message_id varchar(64) not null,
    status varchar(32) not null,
    phase varchar(32) not null,
    execution_attempt bigint not null default 1,
    owner_instance_id varchar(128) not null,
    request_id varchar(64) not null,
    started_at timestamptz,
    completed_at timestamptz,
    failed_at timestamptz,
    failure_code varchar(64),
    failure_detail text,
    hard_deadline_at timestamptz not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_run_tenant_id unique (tenant_id, id),
    constraint fk_agent_run_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_agent_run_session foreign key (tenant_id, session_id)
        references agent_session(tenant_id, id),
    constraint fk_agent_run_triggering_message foreign key (tenant_id, triggering_message_id)
        references agent_message(tenant_id, id),
    constraint ck_agent_run_status check (
        status in ('idle', 'running', 'waiting_for_user', 'waiting_confirmation',
                   'done', 'cancelled', 'failed')
    ),
    constraint ck_agent_run_phase check (
        phase in ('RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL', 'THINKING',
                  'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
    ),
    constraint ck_agent_run_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

alter table agent_message
    add constraint fk_agent_message_run foreign key (tenant_id, run_id)
        references agent_run(tenant_id, id);

create unique index uq_agent_run_one_active
    on agent_run (tenant_id, session_id)
    where is_deleted = false and status in
        ('running', 'waiting_for_user', 'waiting_confirmation');

create table agent_run_event (
    id varchar(64) primary key,
    tenant_id varchar(64) not null,
    session_id varchar(64) not null,
    run_id varchar(64) not null,
    sequence bigint not null,
    type varchar(64) not null,
    payload jsonb not null default '{}'::jsonb,
    occurred_at timestamptz not null,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_run_event_tenant_id unique (tenant_id, id),
    constraint uq_agent_run_event_sequence unique (tenant_id, run_id, sequence),
    constraint uq_agent_run_event_session_sequence unique (tenant_id, session_id, sequence),
    constraint fk_agent_run_event_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_agent_run_event_session foreign key (tenant_id, session_id)
        references agent_session(tenant_id, id),
    constraint fk_agent_run_event_run foreign key (tenant_id, run_id)
        references agent_run(tenant_id, id),
    constraint ck_agent_run_event_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table agent_run_stream (
    run_id varchar(64) primary key,
    tenant_id varchar(64) not null,
    execution_attempt bigint not null,
    phase varchar(32) not null,
    stream_sequence bigint not null default 0,
    thinking text not null default '',
    answer text not null default '',
    checkpointed_at timestamptz,
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint uq_agent_run_stream_tenant_run unique (tenant_id, run_id),
    constraint fk_agent_run_stream_tenant foreign key (tenant_id) references tenant(id),
    constraint fk_agent_run_stream_run foreign key (tenant_id, run_id)
        references agent_run(tenant_id, id),
    constraint ck_agent_run_stream_phase check (
        phase in ('RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL', 'THINKING',
                  'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
    ),
    constraint ck_agent_run_stream_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

create table notification_outbox (
    id bigint generated always as identity primary key,
    tenant_id varchar(64) not null,
    notification_type varchar(64) not null,
    aggregate_type varchar(64) not null,
    aggregate_id varchar(64) not null,
    aggregate_version bigint not null,
    payload jsonb not null,
    published_at timestamptz,
    attempt_count integer not null default 0,
    next_attempt_at timestamptz not null default current_timestamp,
    last_error text,
    created_at timestamptz not null default current_timestamp,
    constraint uq_notification_outbox_tenant_id unique (tenant_id, id),
    constraint fk_notification_outbox_tenant foreign key (tenant_id) references tenant(id),
    constraint ck_notification_outbox_attempt_count check (attempt_count >= 0)
);

create index ix_login_session_active_token
    on login_session (token_hash, expires_at)
    where is_deleted = false and revoked_at is null;

create index ix_agent_session_tenant_updated
    on agent_session (tenant_id, updated_at desc)
    where is_deleted = false;

create index ix_agent_message_session_sequence
    on agent_message (tenant_id, session_id, sequence)
    where is_deleted = false;

create index ix_agent_run_event_session_sequence
    on agent_run_event (tenant_id, session_id, sequence)
    where is_deleted = false;

create index ix_notification_outbox_due
    on notification_outbox (next_attempt_at, id)
    where published_at is null;
