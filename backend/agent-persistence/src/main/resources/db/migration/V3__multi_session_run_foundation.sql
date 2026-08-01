alter table agent_run
    add column owner_user_id varchar(64),
    add column queued_at timestamptz,
    add column lease_expires_at timestamptz,
    add column heartbeat_at timestamptz,
    add column cancellation_requested_at timestamptz,
    add column retry_of_run_id varchar(64),
    add column recovery_attempt_count integer not null default 0,
    add column request_type varchar(32),
    add column request_idempotency_key varchar(256),
    add column request_payload_hash varchar(64),
    add column execution_budget_ms bigint not null default 600000,
    add column active_execution_ms bigint not null default 0,
    add column active_segment_started_at timestamptz;

update agent_run r
   set owner_user_id = s.created_by,
       queued_at = r.created_at
  from agent_session s
 where s.tenant_id = r.tenant_id
   and s.id = r.session_id;

update agent_run
   set heartbeat_at = coalesce(started_at, created_at),
       lease_expires_at = hard_deadline_at,
       active_segment_started_at = coalesce(started_at, created_at)
 where status = 'running';

update agent_run
   set owner_instance_id = null,
       hard_deadline_at = null
 where status <> 'running';

alter table agent_run
    alter column owner_user_id set not null,
    alter column owner_instance_id drop not null,
    alter column hard_deadline_at drop not null,
    add constraint fk_agent_run_owner_user foreign key (tenant_id, owner_user_id)
        references user_account(tenant_id, id),
    add constraint fk_agent_run_retry_of foreign key (tenant_id, retry_of_run_id)
        references agent_run(tenant_id, id),
    add constraint ck_agent_run_execution_attempt check (execution_attempt >= 0),
    add constraint ck_agent_run_recovery_attempt_count check (recovery_attempt_count >= 0),
    add constraint ck_agent_run_execution_budget check (
        execution_budget_ms > 0 and active_execution_ms >= 0
    ),
    add constraint ck_agent_run_request_idempotency check (
        (request_type is null and request_idempotency_key is null and request_payload_hash is null)
        or (
            request_type in ('message_submission', 'run_retry')
            and request_idempotency_key is not null
            and request_payload_hash ~ '^[0-9a-f]{64}$'
        )
    );

alter table agent_run drop constraint ck_agent_run_status;
alter table agent_run add constraint ck_agent_run_status check (
    status in ('idle', 'queued', 'running', 'waiting_for_user', 'waiting_confirmation',
               'done', 'cancelled', 'failed')
);

alter table agent_run drop constraint ck_agent_run_phase;
alter table agent_run add constraint ck_agent_run_phase check (
    phase in ('QUEUED', 'RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL', 'THINKING',
              'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
);

alter table agent_run add constraint ck_agent_run_runtime_state check (
    (
        status = 'queued'
        and execution_attempt = 0
        and queued_at is not null
        and owner_instance_id is null
        and started_at is null
        and hard_deadline_at is null
        and active_segment_started_at is null
        and heartbeat_at is null
        and lease_expires_at is null
    )
    or (
        status = 'running'
        and execution_attempt >= 1
        and owner_instance_id is not null
        and started_at is not null
        and hard_deadline_at is not null
        and active_segment_started_at is not null
        and heartbeat_at is not null
        and lease_expires_at is not null
    )
    or (
        status not in ('queued', 'running')
        and active_segment_started_at is null
        and hard_deadline_at is null
        and heartbeat_at is null
        and lease_expires_at is null
    )
);

drop index uq_agent_run_one_active;
create unique index uq_agent_run_one_active
    on agent_run (tenant_id, session_id)
    where is_deleted = false and status in
        ('queued', 'running', 'waiting_for_user', 'waiting_confirmation');

create unique index uq_agent_run_request_idempotency
    on agent_run (tenant_id, session_id, request_type, request_idempotency_key)
    where request_idempotency_key is not null;

create index ix_agent_run_queued
    on agent_run (queued_at, id)
    where is_deleted = false and status = 'queued';

create index ix_agent_run_user_running
    on agent_run (tenant_id, owner_user_id)
    where is_deleted = false and status = 'running';

create index ix_agent_run_expired_lease
    on agent_run (lease_expires_at, id)
    where is_deleted = false and status = 'running';

create table agent_run_attempt (
    tenant_id varchar(64) not null,
    run_id varchar(64) not null,
    execution_attempt bigint not null,
    owner_instance_id varchar(128) not null,
    status varchar(32) not null,
    resume_reason varchar(64),
    started_at timestamptz not null,
    ended_at timestamptz,
    failure_code varchar(64),
    created_at timestamptz not null default current_timestamp,
    updated_at timestamptz not null default current_timestamp,
    deleted_at timestamptz,
    is_deleted boolean not null default false,
    version bigint not null default 0,
    constraint pk_agent_run_attempt primary key (tenant_id, run_id, execution_attempt),
    constraint fk_agent_run_attempt_run foreign key (tenant_id, run_id)
        references agent_run(tenant_id, id),
    constraint ck_agent_run_attempt_number check (execution_attempt >= 1),
    constraint ck_agent_run_attempt_status check (
        status in ('running', 'completed', 'waiting', 'cancelled', 'failed', 'lost')
    ),
    constraint ck_agent_run_attempt_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    )
);

alter table notification_outbox
    add column updated_at timestamptz not null default current_timestamp,
    add column deleted_at timestamptz,
    add column is_deleted boolean not null default false,
    add column version bigint not null default 0,
    add constraint ck_notification_outbox_soft_delete check (
        (is_deleted = false and deleted_at is null)
        or (is_deleted = true and deleted_at is not null)
    );

drop index ix_notification_outbox_due;
create index ix_notification_outbox_due
    on notification_outbox (next_attempt_at, id)
    where published_at is null and is_deleted = false;

alter table agent_run_stream
    rename column thinking to reasoning;

alter table agent_run_stream
    add column model_turn_sequence bigint not null default 0,
    add column reasoning_expires_at timestamptz,
    add constraint ck_agent_run_stream_execution_attempt check (execution_attempt >= 0),
    add constraint ck_agent_run_stream_model_turn_sequence check (model_turn_sequence >= 0);

alter table agent_run_stream drop constraint ck_agent_run_stream_phase;
alter table agent_run_stream add constraint ck_agent_run_stream_phase check (
    phase in ('QUEUED', 'RECEIVED', 'PREPARING_CONTEXT', 'CONNECTING_MODEL', 'THINKING',
              'ANSWERING', 'PERSISTING', 'COMPLETED', 'FAILED')
);
