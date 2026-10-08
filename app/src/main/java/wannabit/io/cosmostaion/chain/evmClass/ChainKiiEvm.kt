package wannabit.io.cosmostaion.chain.evmClass

import android.os.Parcelable
import com.google.common.collect.ImmutableList
import kotlinx.parcelize.Parcelize
import org.bitcoinj.crypto.ChildNumber
import wannabit.io.cosmostaion.chain.AccountKeyType
import wannabit.io.cosmostaion.chain.BaseChain
import wannabit.io.cosmostaion.chain.CosmosEndPointType
import wannabit.io.cosmostaion.chain.PubKeyType

@Parcelize
class ChainKiiEvm : BaseChain(), Parcelable {

    override var name: String = "Kii"
    override var tag: String = "kii60"
    override var apiName: String = "kii"

    override var accountKeyType = AccountKeyType(PubKeyType.ETH_KECCAK256, "m/44'/60'/0'/0/X")
    override var setParentPath: List<ChildNumber> = ImmutableList.of(
        ChildNumber(44, true), ChildNumber(60, true), ChildNumber.ZERO_HARDENED, ChildNumber.ZERO
    )

    override var cosmosEndPointType: CosmosEndPointType? = CosmosEndPointType.USE_LCD
    override var stakeDenom: String = "akii"
    override var accountPrefix: String = "kii"
    override var grpcHost: String = "grpc-kiichain.mainnet.cosmoslabs.kr:443"
    override var lcdUrl: String = "https://lcd-kiichain.mainnet.cosmoslabs.kr/"

    override var supportEvm: Boolean = true
    override var coinSymbol: String = "KII"
    override var evmRpcURL: String = "https://rpc-evm-kiichain.mainnet.cosmoslabs.kr"
}