package wannabit.io.cosmostaion.chain.fetcher

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.protobuf.Value
import com.sui.rpc.v2.EpochProto
import com.sui.rpc.v2.ObjectProto
import com.sui.rpc.v2.StateServiceProto
import com.sui.rpc.v2.SystemStateProto
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import wannabit.io.cosmostaion.chain.BaseChain
import wannabit.io.cosmostaion.chain.majorClass.SUI_FEE_DEFAULT
import wannabit.io.cosmostaion.chain.majorClass.SUI_FEE_SEND
import wannabit.io.cosmostaion.chain.majorClass.SUI_FEE_STAKE
import wannabit.io.cosmostaion.chain.majorClass.SUI_FEE_UNSTAKE
import wannabit.io.cosmostaion.chain.majorClass.SUI_MAIN_DENOM
import wannabit.io.cosmostaion.chain.majorClass.SUI_TYPE_COIN
import wannabit.io.cosmostaion.common.BaseData
import wannabit.io.cosmostaion.common.jsonRpcResponse
import wannabit.io.cosmostaion.data.model.req.JsonRpcRequest
import wannabit.io.cosmostaion.database.Prefs
import wannabit.io.cosmostaion.sign.SuiJS
import wannabit.io.cosmostaion.ui.tx.genTx.SuiTxType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.CopyOnWriteArrayList

class SuiFetcher(private val chain: BaseChain) {

    var suiSystem: EpochProto.Epoch? = null
    var suiBalances: MutableList<Pair<String?, BigDecimal?>> = mutableListOf()
    val suiObjects: MutableList<ObjectProto.Object> = mutableListOf()
    var suiStakedList: MutableList<StakeReward> = mutableListOf()
    var suiValidators: MutableList<SystemStateProto.Validator> = mutableListOf()
    val suiCoinMeta: MutableMap<String, StateServiceProto.CoinMetadata> = mutableMapOf()
    val suiHistory: MutableList<JsonObject> = mutableListOf()
    var suiApys: MutableList<JsonObject> = mutableListOf()

    fun allAssetValue(isUsd: Boolean? = false): BigDecimal {
        return suiBalanceValueSum(isUsd).add(suiStakedValue(isUsd))
    }

    fun allSuiAmount(): BigDecimal? {
        return suiBalanceAmount(SUI_MAIN_DENOM)?.add(stakedAmount()) ?: BigDecimal.ZERO
    }

    fun allSuiValue(isUsd: Boolean? = false): BigDecimal {
        val amount = allSuiAmount()
        if (amount == BigDecimal.ZERO) return BigDecimal.ZERO
        BaseData.getAsset(chain.apiName, SUI_MAIN_DENOM)?.let { asset ->
            val price = BaseData.getPrice(asset.coinGeckoId, isUsd)
            return price.multiply(amount).movePointLeft(asset.decimals ?: 6)
                .setScale(6, RoundingMode.DOWN)
        }
        return BigDecimal.ZERO
    }

    fun suiBalanceAmount(coinType: String): BigDecimal? {
        suiBalances.firstOrNull { it.first == coinType }?.let { suiCoin ->
            return suiCoin.second
        }
        return BigDecimal.ZERO
    }

    fun suiBalanceValue(coinType: String, isUsd: Boolean? = false): BigDecimal {
        val amount = suiBalanceAmount(coinType)
        if (amount == BigDecimal.ZERO) return BigDecimal.ZERO
        BaseData.getAsset(chain.apiName, coinType)?.let { asset ->
            val price = BaseData.getPrice(asset.coinGeckoId, isUsd)
            return price.multiply(amount).movePointLeft(asset.decimals ?: 6)
                .setScale(6, RoundingMode.DOWN)
        }
        return BigDecimal.ZERO
    }

    private fun suiBalanceValueSum(isUsd: Boolean? = false): BigDecimal {
        var sum = BigDecimal.ZERO
        if (suiBalances.isNotEmpty()) {
            synchronized(suiBalances) {
                val balanceList = CopyOnWriteArrayList(suiBalances)
                val iterator = balanceList.iterator()
                while (iterator.hasNext()) {
                    val balance = iterator.next()
                    balance.first?.let {
                        sum = sum.add(suiBalanceValue(it, isUsd))
                    }
                }
            }
        }
        return sum
    }

    fun stakedAmount(): BigDecimal {
        var staked = BigDecimal.ZERO
        var earned = BigDecimal.ZERO
        suiStakedList.forEach { suiStaked ->
            staked = staked.add(suiStaked.principal.toBigDecimal())
            earned = earned.add(suiStaked.estimatedReward.toBigDecimal())
        }
        return staked.add(earned)
    }

    private fun suiStakedValue(isUsd: Boolean? = false): BigDecimal {
        val amount = stakedAmount()
        if (amount == BigDecimal.ZERO) {
            return BigDecimal.ZERO
        }
        BaseData.getAsset(chain.apiName, SUI_MAIN_DENOM)?.let { asset ->
            val price = BaseData.getPrice(asset.coinGeckoId, isUsd)
            return price.multiply(amount).movePointLeft(asset.decimals ?: 6)
                .setScale(6, RoundingMode.DOWN)
        }
        return BigDecimal.ZERO
    }

    fun principalAmount(): BigDecimal {
        return suiStakedList.sumOf { it.principal }.toBigDecimal()
    }

    fun estimateRewardAmount(): BigDecimal {
        return suiStakedList.sumOf { it.estimatedReward }.toBigDecimal()
    }

    fun suiAllNfts(): MutableList<ObjectProto.Object> {
        return suiObjects.filter { suiObject ->
            val types = suiObject.objectType.lowercase()
            (!types.contains("stakedsui") && !types.contains("coin"))

//            val types = suiObject["data"].asJsonObject["type"].asString.lowercase()
//            (!types.contains("stakedsui") && !types.contains("coin"))
        }.toMutableList()
    }

    fun suiBaseFee(txType: SuiTxType): BigDecimal {
        when (txType) {
            SuiTxType.SUI_SEND_COIN, SuiTxType.SUI_SEND_NFT -> {
                return SUI_FEE_SEND.toBigDecimal()
            }

            SuiTxType.SUI_STAKE -> {
                return SUI_FEE_STAKE.toBigDecimal()
            }

            SuiTxType.SUI_UNSTAKE -> {
                return SUI_FEE_UNSTAKE.toBigDecimal()
            }

            else -> return SUI_FEE_DEFAULT.toBigDecimal()
        }
    }

    fun getSuiGrpc(): Pair<String, Int> {
        val endPoint = Prefs.getGrpcEndpoint(chain)
        return if (endPoint.isNotEmpty() && endPoint.split(":").count() == 2) {
            val host = endPoint.split(":")[0].trim()
            val port = endPoint.split(":").getOrNull(1)?.trim()?.toIntOrNull() ?: 443
            Pair(host, port)

        } else {
            if (chain.grpcHost.split(":").count() == 2) {
                val host = chain.grpcHost.split(":")[0].trim()
                val port = chain.grpcHost.split(":").getOrNull(1)?.trim()?.toIntOrNull() ?: 443
                Pair(host, port)
            } else {
                Pair(chain.grpcHost, chain.grpcPort)
            }
        }
    }

    fun getChannel(): ManagedChannel? {
        return if (getSuiGrpc().first.isEmpty()) {
            null
        } else {
            ManagedChannelBuilder.forAddress(
                getSuiGrpc().first, getSuiGrpc().second
            ).useTransportSecurity().build()
        }
    }

    fun suiRpc(): String {
        val endpoint = Prefs.getEvmRpcEndpoint(chain)
        return if (endpoint?.isNotEmpty() == true) {
            endpoint
        } else {
            chain.mainUrl
        }
    }

    fun buildPoolMap(systemState: SystemStateProto.SystemState): Map<String, PoolInfo> {
        return systemState.validators.activeValidatorsList.associate { activeValidator ->
            activeValidator.stakingPool.id to PoolInfo(
                activeValidator.address,
                activeValidator.stakingPool.exchangeRates.id
            )
        }
    }

    fun rate(suiAmount: Long, poolTokenAmount: Long): Double =
        if (suiAmount == 0L) 1.0 else poolTokenAmount.toDouble() / suiAmount.toDouble()

    private fun referenceGasPrice(): String {
        val suixReferenceGasPriceRequest =
            JsonRpcRequest(method = "suix_getReferenceGasPrice", params = listOf())
        val suixReferenceGasPriceResponse = jsonRpcResponse(
            suiRpc(), suixReferenceGasPriceRequest
        )
        val suixReferenceGasPriceJsonObject = Gson().fromJson(
            suixReferenceGasPriceResponse.body?.string(), JsonObject::class.java
        )
        return suixReferenceGasPriceJsonObject["result"].asString
    }

    private fun suixCoins(): JsonArray {
        val suixGetCoinsRequest = JsonRpcRequest(
            method = "suix_getCoins", params = listOf(chain.mainAddress, SUI_MAIN_DENOM, null, 1)
        )
        val suixGetCoinsResponse = jsonRpcResponse(chain.mainUrl, suixGetCoinsRequest)
        val suixGetCoinsJsonObject = Gson().fromJson(
            suixGetCoinsResponse.body?.string(), JsonObject::class.java
        )

        return suixGetCoinsJsonObject["result"].asJsonObject["data"].asJsonArray
    }

    fun buildStakingRequest(suiJs: SuiJS, amount: String, validatorAddress: String?): String? {
        val gasPrice = referenceGasPrice()
        val coinDatas = suixCoins()

        return if (coinDatas.size() > 0) {
            val coinData = coinDatas[0].asJsonObject
            val gasBudget = suiBaseFee(SuiTxType.SUI_STAKE)
            val coinObjectId = coinData["coinObjectId"].asString
            val version = coinData["version"].asString
            val digest = coinData["digest"].asString

            val buildStakingRequestFunction =
                """function buildStakingRequestFunction() {
                const txHex = buildStakingRequest('${amount}', '${validatorAddress}', '${chain.mainAddress}', 
                '${gasPrice}', '${gasBudget}', '${coinObjectId}', '${version}', '${digest}');
                return txHex;
                }""".trimMargin()
            suiJs.mergeFunction(buildStakingRequestFunction)
            return suiJs.executeFunction("buildStakingRequestFunction()")

        } else {
            ""
        }
    }

    private fun suiObject(objectId: String): JsonObject {
        val suiGetObjectRequest = JsonRpcRequest(
            method = "sui_getObject", params = listOf(objectId, mapOf("showContent" to false))
        )
        val suiGetObjectResponse = jsonRpcResponse(chain.mainUrl, suiGetObjectRequest)
        val suiGetObjectJsonObject = Gson().fromJson(
            suiGetObjectResponse.body?.string(), JsonObject::class.java
        )

        return suiGetObjectJsonObject["result"].asJsonObject["data"].asJsonObject
    }

    fun buildUnstakingRequest(suiJs: SuiJS, objectId: String): String? {
        val gasPrice = referenceGasPrice()
        val coinDatas = suixCoins()

        return if (coinDatas.size() > 0) {
            val coinData = coinDatas[0].asJsonObject
            val gasBudget = suiBaseFee(SuiTxType.SUI_UNSTAKE)
            val coinObjectId = coinData["coinObjectId"].asString
            val version = coinData["version"].asString
            val digest = coinData["digest"].asString

            val stakedObject = suiObject(objectId)
            val stakedObjectVersion = stakedObject["version"].asString
            val stakedObjectDigest = stakedObject["digest"].asString

            val buildUnstakingRequestFunction =
                """function buildUnstakingRequestFunction() {
                const txHex = buildUnstakingRequest('${chain.mainAddress}', '${gasPrice}', '${gasBudget}', '${coinObjectId}', '${version}', '${digest}',
                '${objectId}', '${stakedObjectVersion}', '${stakedObjectDigest}');
                return txHex;
                }""".trimMargin()
            suiJs.mergeFunction(buildUnstakingRequestFunction)
            return suiJs.executeFunction("buildUnstakingRequestFunction()")

        } else {
            ""
        }
    }
}

data class PoolInfo(val validatorAddress: String, val exchangeRatesTableId: String)
data class StakeReward(
    val objectId: String,
    val poolId: String,
    val validatorAddress: String,
    val principal: Long,
    val activationEpoch: Long,
    val isPending: Boolean,
    val estimatedReward: Long
)

fun String.suiNormalizeType(): String {
    return Regex("0x0*([0-9a-fA-F]+)(?=::)").replace(this) { "0x${it.groupValues[1]}" }
}

fun String.suiIsCoinType(): Boolean {
    return this.suiNormalizeType().startsWith(SUI_TYPE_COIN)
}

fun String?.suiCoinType(): String? {
    val normalized = this?.suiNormalizeType()
    if (normalized?.suiIsCoinType() == false) {
        return null
    }
    val regex = Regex("<(.+)>")
    normalized?.let {
        val matchResult = regex.find(it)
        return matchResult?.groups?.get(1)?.value
    }
    return null
}

fun String?.suiCoinSymbol(): String? {
    val regex = Regex("::([a-zA-Z0-9_]+)(?:<.*>)?$")
    this?.let {
        val matchResult = regex.find(it)
        return matchResult?.groups?.get(1)?.value
    }
    return "Unknown"
}

fun JsonObject?.assetImg(): String {
    return try {
        this?.get("iconUrl")?.asString ?: ""
    } catch (e: Exception) {
        ""
    }
}

fun Value.getStringField(key: String): String? = structValue.fieldsMap[key]?.stringValue

fun JsonObject.moveRawNftUrlString(): String? {
    return try {
        this["display"].asJsonObject["data"].asJsonObject["image_url"].asString
    } catch (e: Exception) {
        null
    }
}

fun JsonObject.moveNftUrl(): String? {
    var urlString: String?
    moveRawNftUrlString()?.let { url ->
        if (url.startsWith("ipfs://")) {
            urlString = url.replace("ipfs://", "https://ipfs.io/ipfs/")
            return urlString
        }
        return url
    }
    return null
}

fun JsonObject.moveValidatorImg(): String? {
    if (this.has("imageUrl")) {
        return this["imageUrl"].asString
    }
    return null
}

fun JsonObject.moveValidatorName(): String {
    return this["name"].asString ?: ""
}

fun JsonObject.moveValidatorCommission(): BigDecimal {
    return this["commissionRate"].asString.toBigDecimal().movePointLeft(2)
        .setScale(2, RoundingMode.DOWN)
}

fun JsonObject.suiValidatorVp(): BigDecimal {
    return this["stakingPoolSuiBalance"].asString.toBigDecimal().movePointLeft(9)
        .setScale(9, RoundingMode.DOWN)
}