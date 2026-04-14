package com.evolution.natseffect.jetstream

package object impl {

  type JJetStream              = io.nats.client.JetStream
  type JJetStreamManagement    = io.nats.client.JetStreamManagement
  type JStreamContext          = io.nats.client.StreamContext
  type JBaseConsumerContext    = io.nats.client.BaseConsumerContext
  type JConsumerContext        = io.nats.client.ConsumerContext
  type JOrderedConsumerContext = io.nats.client.OrderedConsumerContext
  type JMessageConsumer        = io.nats.client.MessageConsumer
  type JJetStreamSubscription  = io.nats.client.JetStreamSubscription
  type JKeyValue               = io.nats.client.KeyValue
  type JKeyValueManagement     = io.nats.client.KeyValueManagement
  type JConsumerInfo           = io.nats.client.api.ConsumerInfo
  type JAccountStatistics      = io.nats.client.api.AccountStatistics
  type JStreamInfo             = io.nats.client.api.StreamInfo

}
