alter table tenant_membership drop constraint fk_tenant_membership_user;
alter table tenant_membership drop constraint fk_tenant_membership_tenant;
alter table agent_session drop constraint fk_agent_session_creator;
alter table agent_session drop constraint fk_agent_session_tenant;
alter table agent_message drop constraint fk_agent_message_run;
alter table agent_message drop constraint fk_agent_message_author;
alter table agent_message drop constraint fk_agent_message_session;
alter table agent_message drop constraint fk_agent_message_tenant;
alter table agent_run drop constraint fk_agent_run_retry_of;
alter table agent_run drop constraint fk_agent_run_owner_user;
alter table agent_run drop constraint fk_agent_run_triggering_message;
alter table agent_run drop constraint fk_agent_run_session;
alter table agent_run drop constraint fk_agent_run_tenant;
alter table agent_run_event drop constraint fk_agent_run_event_run;
alter table agent_run_event drop constraint fk_agent_run_event_session;
alter table agent_run_event drop constraint fk_agent_run_event_tenant;
alter table agent_run_stream drop constraint fk_agent_run_stream_run;
alter table agent_run_stream drop constraint fk_agent_run_stream_tenant;
alter table agent_run_attempt drop constraint fk_agent_run_attempt_run;
alter table notification_outbox drop constraint fk_notification_outbox_tenant;
alter table user_account drop constraint fk_user_account_tenant;

alter table tenant drop constraint tenant_pkey;
alter table tenant rename column id to uuid;
alter table tenant add column id bigint generated always as identity;
alter table tenant add constraint tenant_pkey primary key (id);
alter table tenant add constraint uq_tenant_uuid unique (uuid);

alter table user_account drop constraint user_account_pkey;
alter table user_account rename column id to uuid;
alter table user_account add column id bigint generated always as identity;
alter table user_account add constraint user_account_pkey primary key (id);
alter table user_account add constraint uq_user_account_uuid unique (uuid);

alter table tenant_membership drop constraint tenant_membership_pkey;
alter table tenant_membership rename column id to uuid;
alter table tenant_membership add column id bigint generated always as identity;
alter table tenant_membership add constraint tenant_membership_pkey primary key (id);
alter table tenant_membership add constraint uq_tenant_membership_uuid unique (uuid);

alter table agent_session drop constraint agent_session_pkey;
alter table agent_session rename column id to uuid;
alter table agent_session add column id bigint generated always as identity;
alter table agent_session add constraint agent_session_pkey primary key (id);
alter table agent_session add constraint uq_agent_session_uuid unique (uuid);

alter table agent_message drop constraint agent_message_pkey;
alter table agent_message rename column id to uuid;
alter table agent_message add column id bigint generated always as identity;
alter table agent_message add constraint agent_message_pkey primary key (id);
alter table agent_message add constraint uq_agent_message_uuid unique (uuid);

drop index ix_agent_run_queued;
drop index ix_agent_run_expired_lease;
alter table agent_run drop constraint agent_run_pkey;
alter table agent_run rename column id to uuid;
alter table agent_run add column id bigint generated always as identity;
alter table agent_run add constraint agent_run_pkey primary key (id);
alter table agent_run add constraint uq_agent_run_uuid unique (uuid);
create index ix_agent_run_queued on agent_run (queued_at, id)
    where is_deleted = false and status = 'queued';
create index ix_agent_run_expired_lease on agent_run (lease_expires_at, id)
    where is_deleted = false and status = 'running';

alter table agent_run_event drop constraint agent_run_event_pkey;
alter table agent_run_event rename column id to uuid;
alter table agent_run_event add column id bigint generated always as identity;
alter table agent_run_event add constraint agent_run_event_pkey primary key (id);
alter table agent_run_event add constraint uq_agent_run_event_uuid unique (uuid);

alter table agent_run_stream drop constraint agent_run_stream_pkey;
alter table agent_run_stream add column uuid varchar(64);
update agent_run_stream set uuid = run_id where uuid is null;
alter table agent_run_stream alter column uuid set not null;
alter table agent_run_stream add column id bigint generated always as identity;
alter table agent_run_stream add constraint agent_run_stream_pkey primary key (id);
alter table agent_run_stream add constraint uq_agent_run_stream_uuid unique (uuid);

alter table agent_run_attempt drop constraint pk_agent_run_attempt;
alter table agent_run_attempt add column uuid varchar(64);
update agent_run_attempt
   set uuid = 'attempt_' || md5(tenant_id || ':' || run_id || ':' || execution_attempt::text)
 where uuid is null;
alter table agent_run_attempt alter column uuid set not null;
alter table agent_run_attempt add column id bigint generated always as identity;
alter table agent_run_attempt add constraint agent_run_attempt_pkey primary key (id);
alter table agent_run_attempt add constraint uq_agent_run_attempt_uuid unique (uuid);
alter table agent_run_attempt add constraint uq_agent_run_attempt_execution
    unique (tenant_id, run_id, execution_attempt);

alter table notification_outbox add column uuid varchar(64);
update notification_outbox set uuid = 'notification_' || id::text where uuid is null;
alter table notification_outbox alter column uuid set not null;
alter table notification_outbox add constraint uq_notification_outbox_uuid unique (uuid);

alter table user_account add constraint fk_user_account_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table tenant_membership add constraint fk_tenant_membership_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table tenant_membership add constraint fk_tenant_membership_user
    foreign key (tenant_id, user_id) references user_account(tenant_id, uuid);
alter table agent_session add constraint fk_agent_session_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table agent_session add constraint fk_agent_session_creator
    foreign key (tenant_id, created_by) references user_account(tenant_id, uuid);
alter table agent_message add constraint fk_agent_message_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table agent_message add constraint fk_agent_message_session
    foreign key (tenant_id, session_id) references agent_session(tenant_id, uuid);
alter table agent_message add constraint fk_agent_message_author
    foreign key (tenant_id, author_user_id) references user_account(tenant_id, uuid);
alter table agent_run add constraint fk_agent_run_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table agent_run add constraint fk_agent_run_session
    foreign key (tenant_id, session_id) references agent_session(tenant_id, uuid);
alter table agent_run add constraint fk_agent_run_triggering_message
    foreign key (tenant_id, triggering_message_id) references agent_message(tenant_id, uuid);
alter table agent_run add constraint fk_agent_run_owner_user
    foreign key (tenant_id, owner_user_id) references user_account(tenant_id, uuid);
alter table agent_run add constraint fk_agent_run_retry_of
    foreign key (tenant_id, retry_of_run_id) references agent_run(tenant_id, uuid);
alter table agent_message add constraint fk_agent_message_run
    foreign key (tenant_id, run_id) references agent_run(tenant_id, uuid);
alter table agent_run_event add constraint fk_agent_run_event_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table agent_run_event add constraint fk_agent_run_event_session
    foreign key (tenant_id, session_id) references agent_session(tenant_id, uuid);
alter table agent_run_event add constraint fk_agent_run_event_run
    foreign key (tenant_id, run_id) references agent_run(tenant_id, uuid);
alter table agent_run_stream add constraint fk_agent_run_stream_tenant
    foreign key (tenant_id) references tenant(uuid);
alter table agent_run_stream add constraint fk_agent_run_stream_run
    foreign key (tenant_id, run_id) references agent_run(tenant_id, uuid);
alter table agent_run_attempt add constraint fk_agent_run_attempt_run
    foreign key (tenant_id, run_id) references agent_run(tenant_id, uuid);
alter table notification_outbox add constraint fk_notification_outbox_tenant
    foreign key (tenant_id) references tenant(uuid);
