const flightLabels = {
  paused: "已暂停",
  running: "监控中",
  checking: "查询报价",
  preparing: "核对预订",
  ready: "预算达标",
  submitting: "正在下单",
  pending_payment: "订单待支付",
  unknown: "结果待核对",
};
const railLabels = {
  pending: "待运行",
  running: "运行中",
  paused: "已暂停",
  success: "订单已生成",
  failed: "失败",
  cancelled: "已取消",
};

export function taskActions(kind, row) {
  const status = row.status;
  if (kind === "rail")
    return {
      start: ["pending", "paused"].includes(status),
      pause: status === "running",
      edit: ["pending", "paused", "failed", "cancelled"].includes(status),
      cancel: !["success", "cancelled"].includes(status),
      remove: status !== "running",
    };
  return {
    start: ["paused", "ready"].includes(status),
    pause: ["running", "checking", "preparing"].includes(status),
    edit: ["paused", "ready"].includes(status) && !row.order_id,
    cancel: false,
    remove: ![
      "submitting",
      "unknown",
      "pending_payment",
      "checking",
      "preparing",
    ].includes(status),
  };
}

export function normalizeTask(kind, row) {
  const flight = kind === "flight";
  const config = flight ? row.config : row;
  const busy = ["checking", "preparing", "submitting"].includes(row.status);
  const status = busy ? "running" : row.status;
  const time = row.updated_at || "";
  const timestamp = time
    ? Date.parse(
        flight || /Z$|[+-]\d\d:\d\d$/.test(time) ? time : time + "+08:00",
      )
    : 0;
  return {
    key: `${kind}:${row.id}`,
    id: row.id,
    kind,
    provider: flight ? config.provider : "12306",
    name: row.name,
    origin: flight ? config.origin_name : row.from_station,
    destination: flight ? config.destination_name : row.to_station,
    date: flight ? config.departure_date : row.train_date,
    target: flight ? config.flight_number : row.train_codes || "符合筛选的车次",
    status,
    sourceStatus: row.status,
    statusLabel: (flight ? flightLabels : railLabels)[row.status] || row.status,
    message: flight ? row.message : row.result_message,
    total: flight ? row.last_result?.total : null,
    indicativePrice: flight ? row.last_result?.indicative_price : null,
    budget: flight ? config.max_total : null,
    orderId: row.order_id,
    timestamp,
    actions: taskActions(kind, row),
    record: row,
    detailPath: `/task/${kind}/${row.id}`,
    editPath: `/edit-task/${kind}/${row.id}`,
  };
}
