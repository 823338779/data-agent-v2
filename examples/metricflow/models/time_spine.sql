-- 连续日历供累计和时间偏移使用，包含样例起始月份之前的一个月。
select cast(day as date) as date_day
from generate_series(date '2025-12-01', date '2026-02-28', interval 1 day) as dates(day)
