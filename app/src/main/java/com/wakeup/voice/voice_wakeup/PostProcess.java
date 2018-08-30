package com.wakeup.voice.voice_wakeup;

import Jama.Matrix;

public class PostProcess {

    public double DCT(double[] a, double[] b) {
        Matrix A = new Matrix(a, 1);
        Matrix B = new Matrix(b, 1);

        Matrix C = A.times(B.transpose());
        double num = C.get(0, 0);

        double denom = A.normF() * B.normF();//余弦值
        double cds = num / denom;//归一化
        cds = 0.5 + 0.5 * cds;
        return cds;
    }

    public double[][] DCT(double[][] a, double[][] b) {
        Matrix A = new Matrix(a);
        Matrix B = new Matrix(b);

        Matrix C = A.times(B.transpose());

        double denom = A.normF() * B.normF();//余弦值

        Matrix CDS = C.times(1/denom);//归一化
        CDS = CDS.times(0.5);
        Matrix M = new Matrix(CDS.getRowDimension(),CDS.getColumnDimension(),0.5);
        CDS = CDS.plus(M);
//        CDS = 0.5 + 0.5 * CDS;
        return CDS.getArray();
    }
}
