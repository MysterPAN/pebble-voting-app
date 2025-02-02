package vote.pebble.vdf;

import vote.pebble.common.HashValue;
import vote.pebble.common.Util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class PietrzakSimpleVDF implements VDF {
    private static final int DELTA = 4096;
    private static final BigInteger TWO_POW_DELTA = BigInteger.TWO.pow(DELTA);

    public final long time;

    public PietrzakSimpleVDF(long time) {
        assert time > 0 && time % 2 == 0;
        this.time = time;
    }

    private static BigInteger repeatSquare(BigInteger x, long t, BigInteger n, BigInteger p, BigInteger q) {
        assert p.multiply(q).equals(n);
        var phi = p.subtract(BigInteger.ONE).multiply(q.subtract(BigInteger.ONE));
        var e = BigInteger.TWO.modPow(BigInteger.valueOf(t), phi);
        return x.modPow(e, n);
    }

    private static BigInteger repeatSquare(BigInteger x, long t, BigInteger n) {
        if (t == 0)
            return x;
        assert t > 0;
        while (t >= DELTA) {
            x = x.modPow(TWO_POW_DELTA, n);
            t -= DELTA;
        }
        if (t == 0)
            return x;
        return x.modPow(BigInteger.TWO.pow((int) t), n);
    }

    private static BigInteger hash(MessageDigest md, BigInteger mu, BigInteger x, BigInteger y, long t, int length) {
        md.reset();
        md.update(Util.natToBytes(mu, length));
        md.update(Util.natToBytes(x, length));
        md.update(Util.natToBytes(y, length));
        md.update(Util.longToBytes(t));
        return Util.natFromBytes(md.digest(), 0, 16); // truncate to 128 bits
    }

    @Override
    public Solution create() {
        var random = new SecureRandom();
        var p = BigInteger.probablePrime(1024, random);
        var q = BigInteger.probablePrime(1024, random);
        var n = p.multiply(q);
        BigInteger x;
        do {
            x = new BigInteger(2048, random);
        } while (x.compareTo(n) >= 0);
        var y = repeatSquare(x, time, n, p, q);
        var input = Util.concat(Util.natToBytes(n, 256), Util.natToBytes(x, 256));
        var output = Util.natToBytes(y, 256);
        var proof = Util.natToBytes(p, 128);
        return new Solution(input, output, proof);
    }

    @Override
    public Solution solve(byte[] input) {
        assert input.length % 2 == 0;
        int length = input.length / 2;
        var md = HashValue.createMessageDigest();
        long t = time;
        var n = Util.natFromBytes(input, 0, length);
        var x = Util.natFromBytes(input, length, length).remainder(n);
        var y = repeatSquare(x, t, n);
        var output = Util.natToBytes(y, length);
        var proof = new ByteArrayOutputStream();
        while (t > DELTA) {
            assert t % 2 == 0;
            long halfT = t / 2;
            var muRoot = repeatSquare(x, halfT - 1, n);
            try {
                proof.write(Util.natToBytes(muRoot, length));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var r = hash(md, muRoot, x, y, t, length);
            // we send the root of mu so that mu will certainly be a quadratic residue
            var mu = muRoot.multiply(muRoot).remainder(n);
            x = x.modPow(r, n).multiply(mu).remainder(n); // x' = x^r * mu
            y = mu.modPow(r, n).multiply(y).remainder(n); // y' = mu^r * y
            if (halfT % 2 == 0) {
                t = halfT;
            } else {
                t = halfT + 1;
                y = y.multiply(y).remainder(n);
            }
        }
        return new Solution(input, output, proof.toByteArray());
    }

    @Override
    public boolean verify(Solution solution) {
        assert solution.input.length % 2 == 0;
        int length = solution.input.length / 2;
        var n = Util.natFromBytes(solution.input, 0, length);
        var x = Util.natFromBytes(solution.input, length, length).remainder(n);
        var y = Util.natFromBytes(solution.output);
        if (y.compareTo(n) >= 0)
            return false; // y should be minimal
        if (solution.proof.length * 2 == length) {
            // proof consists of one of the two factors of n
            var p = Util.natFromBytes(solution.proof);
            var divRem = n.divideAndRemainder(p);
            var q = divRem[0];
            var rem = divRem[1];
            if (!(BigInteger.ZERO.equals(rem)
                    && p.isProbablePrime(40)
                    && q.isProbablePrime(40)))
                return false;
            return repeatSquare(x, time, n, p, q).equals(y);
        }
        var md = HashValue.createMessageDigest();
        var proof = ByteBuffer.wrap(solution.proof);
        var muRootBytes = new byte[length];
        long t = time;
        while (t > DELTA) {
            assert t % 2 == 0;
            long halfT = t / 2;
            try {
                proof.get(muRootBytes);
            } catch (BufferUnderflowException e) {
                return false;
            }
            var muRoot = Util.natFromBytes(muRootBytes);
            var r = hash(md, muRoot, x, y, t, length);
            // we send the root of mu so that mu will certainly be a quadratic residue
            var mu = muRoot.multiply(muRoot).remainder(n);
            x = x.modPow(r, n).multiply(mu).remainder(n); // x' = x^r * mu
            y = mu.modPow(r, n).multiply(y).remainder(n); // y' = mu^r * y
            if (halfT % 2 == 0) {
                t = halfT;
            } else {
                t = halfT + 1;
                y = y.multiply(y).remainder(n);
            }
        }
        return repeatSquare(x, t, n).equals(y);
    }
}
