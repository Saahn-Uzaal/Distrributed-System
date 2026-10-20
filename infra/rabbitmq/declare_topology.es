#!/usr/bin/env escript
%% T04 — Declare RabbitMQ topology theo spec mục 9 (idempotent, chạy lại được).
%% Chạy trong one-off container trên mạng ridehailing_net (kết nối AMQP qua DNS "rabbitmq"):
%%   docker run --rm -e RABBITMQ_USER -e RABBITMQ_PASSWORD -e ERL_LIBS=/opt/rabbitmq/plugins \
%%     --network ridehailing_net -v <repo>/infra/rabbitmq:/topo:ro \
%%     rabbitmq:3-management-alpine escript /topo/declare_topology.es
%% Topology (spec mục 9 + sửa F02 đợt 19/10/2026 — contract events.md §3.5/§3.6:
%% PaymentSucceeded/PaymentFailed được Payment phát cho Trip):
%%   exchange topic durable : ride.events        (event fan-out)
%%   exchange topic durable : ride.events.dlx    (dead-letter)
%%   inbox queues (durable, dead-letter sang ride.events.dlx):
%%     matching.inbox  <- trip.requested          (Trip -> Matching)
%%     trip.inbox      <- matching.#              (Matching -> Trip: proposed + unavailable)
%%                       <- payment.#              (Payment -> Trip: succeeded + failed) [F02]
%%     payment.inbox   <- trip.completed          (Trip -> Payment)
%%   DLQs (durable, bind vào ride.events.dlx theo routing key = tên queue):
%%     matching.dlq, trip.dlq, payment.dlq

%% AMQP 0-9-1 method records — copy nguyên văn từ rabbit_common/include/rabbit_framing.hrl (3.13.7)
-record('exchange.declare', {ticket = 0, exchange, type = <<"direct">>, passive = false, durable = false, auto_delete = false, internal = false, nowait = false, arguments = []}).
-record('queue.declare',    {ticket = 0, queue = <<"">>, passive = false, durable = false, exclusive = false, auto_delete = false, nowait = false, arguments = []}).
-record('queue.bind',       {ticket = 0, queue = <<"">>, exchange, routing_key = <<"">>, nowait = false, arguments = []}).

main(_) ->
    User = os:getenv("RABBITMQ_USER"),
    Pass = os:getenv("RABBITMQ_PASSWORD"),
    Host = "rabbitmq",

    {ok, _} = application:ensure_all_started(amqp_client),
    %% amqp_uri:parse/1 trả về record #amqp_params_network{} sẵn dùng cho amqp_connection:start/1
    {ok, Params} = amqp_uri:parse("amqp://" ++ User ++ ":" ++ Pass ++ "@" ++ Host),
    {ok, C} = amqp_connection:start(Params),
    {ok, Ch} = amqp_connection:open_channel(C),

    %% Exchanges: topic, durable (declare idempotent)
    ok_call(amqp_channel:call(Ch, #'exchange.declare'{exchange = <<"ride.events">>, type = <<"topic">>, durable = true})),
    ok_call(amqp_channel:call(Ch, #'exchange.declare'{exchange = <<"ride.events.dlx">>, type = <<"topic">>, durable = true})),

    %% Inbox queues: durable + dead-letter sang ride.events.dlx; bind vào ride.events
    [ begin
        {Q, Dlk, Key} = T,
        %% AMQP 0-9-1 field table ở client 3.13.7: list of {Key, TypeAtom, Value}
        %% (kiểm chứng từ beam rabbit_binary_generator — generate_table/1)
        ok_call(amqp_channel:call(Ch, #'queue.declare'{
            queue = Q, durable = true,
            arguments = [{<<"x-dead-letter-exchange">>, longstr, <<"ride.events.dlx">>},
                         {<<"x-dead-letter-routing-key">>, longstr, Dlk}]})),
        ok_call(amqp_channel:call(Ch, #'queue.bind'{queue = Q, exchange = <<"ride.events">>, routing_key = Key}))
    end || T <- [ {<<"matching.inbox">>, <<"matching.dlq">>, <<"trip.requested">>},
                 {<<"trip.inbox">>,     <<"trip.dlq">>,     <<"matching.#">>},
                 {<<"payment.inbox">>,  <<"payment.dlq">>,  <<"trip.completed">>} ] ],

    %% F02 (sửa 19/10/2026): Payment -> Trip — trip.inbox nhận payment.succeeded + payment.failed
    %% qua routing key "payment.#" (catalog events.md §3.5/§3.6). Declare idempotent, chạy lại an toàn.
    ok_call(amqp_channel:call(Ch, #'queue.bind'{queue = <<"trip.inbox">>, exchange = <<"ride.events">>, routing_key = <<"payment.#">>})),

    %% DLQs: durable, bind vào ride.events.dlx theo routing key = tên queue
    [ begin
        ok_call(amqp_channel:call(Ch, #'queue.declare'{queue = Q, durable = true})),
        ok_call(amqp_channel:call(Ch, #'queue.bind'{queue = Q, exchange = <<"ride.events.dlx">>, routing_key = Q}))
    end || Q <- [<<"matching.dlq">>, <<"trip.dlq">>, <<"payment.dlq">>] ],

    io:format("T04: topology declared over AMQP (host=~s).~n", [Host]),
    amqp_channel:close(Ch),
    amqp_connection:close(C).

%% amqp_channel:call trả về reply method đã decode (KHÔNG bọc {ok,_}):
%%   exchange.declare -> 'exchange.declare_ok'                 (atom trần)
%%   queue.declare    -> {queue.declare_ok, Q, Msgs, Consumers}
%%   queue.bind       -> 'queue.bind_ok'                       (atom trần)
ok_call(ok) -> ok;
ok_call({ok, _}) -> ok;
ok_call(Reply) ->
    case is_ok_reply(Reply) of
        true -> ok;
        false -> erlang:error({topology_declare_failed, Reply})
    end.

is_ok_reply(A) when is_atom(A) -> ends_ok(atom_to_list(A));
is_ok_reply(T) when is_tuple(T), is_atom(element(1, T)) -> ends_ok(atom_to_list(element(1, T)));
is_ok_reply(_) -> false.

ends_ok(L) -> length(L) > 3 andalso lists:suffix("_ok", L).
