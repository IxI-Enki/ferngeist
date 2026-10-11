package com.tamimarafat.ferngeist.feature.chat

import com.tamimarafat.ferngeist.core.model.GatewaySource
import com.tamimarafat.ferngeist.core.model.repository.GatewaySourceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** Empty [GatewaySourceRepository] for chat view-model tests. */
class FakeGatewaySourceRepository : GatewaySourceRepository {
    override fun getGateways(): Flow<List<GatewaySource>> = emptyFlow()

    override suspend fun addGateway(gateway: GatewaySource) = Unit

    override suspend fun updateGateway(gateway: GatewaySource) = Unit

    override suspend fun deleteGateway(id: String) = Unit

    override suspend fun getGateway(id: String): GatewaySource? = null

    override suspend fun getGatewayByGatewayId(gatewayId: String): GatewaySource? = null
}
