package net.corda.core.internal

import net.corda.core.identity.Party
import java.security.CodeSigner
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.jar.JarEntry
import java.util.jar.JarInputStream

/**
 * Utility class which provides the ability to extract a list of signing parties from a [JarInputStream].
 */
object JarSignatureCollector {

    /** @see <https://docs.oracle.com/javase/8/docs/technotes/guides/jar/jar.html#Signed_JAR_File> */
    private val unsignableEntryName = "META-INF/(?:.*[.](?:SF|DSA|RSA)|SIG-.*)".toRegex()

    /**
     * Returns an ordered list of every [Party] which has signed every signable item in the given [JarInputStream].
     *
     * @param jar The open [JarInputStream] to collect signing parties from.
     * @throws InvalidJarSignersException If the signer sets for any two signable items are different from each other.
     */
    fun collectSigners(jar: JarInputStream): List<PublicKey> {
        val signerSets = jar.fileSignerSets
        if (signerSets.isEmpty()) return emptyList()

        val (firstFile, firstSignerSet) = signerSets.first()
        for ((otherFile, otherSignerSet) in signerSets.subList(1, signerSets.size)) {
            if (otherSignerSet != firstSignerSet) throw InvalidJarSignersException(
                """
                Mismatch between signers ${firstSignerSet.toOrderedPublicKeys()} for file $firstFile
                and signers ${otherSignerSet.toOrderedPublicKeys()} for file ${otherFile}.
                See https://docs.corda.net/design/data-model-upgrades/signature-constraints.html for details of the
                constraints applied to attachment signatures.
                """.trimIndent().replace('\n', ' '))
        }

        return firstSignerSet.toOrderedPublicKeys()
    }

    private val JarInputStream.fileSignerSets: List<Pair<String, Set<CodeSigner>>> get() =
            entries.thatAreSignable.shreddedFrom(this).toFileSignerSet().toList()

    private val Sequence<JarEntry>.thatAreSignable: Sequence<JarEntry> get() =
            filterNot { entry -> entry.isDirectory || unsignableEntryName.matches(entry.name) }

    private fun Sequence<JarEntry>.shreddedFrom(jar: JarInputStream): Sequence<JarEntry> = map { entry ->
        val shredder = ByteArray(1024) // can't share or re-use this, as it's used to compute CRCs during shredding
        entry.apply {
            while (jar.read(shredder) != -1) { // Must read entry fully for codeSigners to be valid.
                // Do nothing.
            }
        }
    }

    private fun Sequence<JarEntry>.toFileSignerSet(): Sequence<Pair<String, Set<CodeSigner>>> =
            map { entry -> entry.name to (entry.codeSigners?.toSet() ?: emptySet()) }

    private fun Set<CodeSigner>.toOrderedPublicKeys(): List<PublicKey> = map {
        (it.signerCertPath.certificates[0] as X509Certificate).publicKey
    }.sortedBy { it.hash} // Sorted for determinism.

    private val JarInputStream.entries get(): Sequence<JarEntry> = generateSequence(nextJarEntry) { nextJarEntry }
}

class InvalidJarSignersException(msg: String) : Exception(msg)