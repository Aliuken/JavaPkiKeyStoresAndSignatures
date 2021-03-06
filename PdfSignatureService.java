package com.aliuken.pki.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;

import com.itextpdf.text.Rectangle;
import com.itextpdf.text.pdf.PdfDate;
import com.itextpdf.text.pdf.PdfDictionary;
import com.itextpdf.text.pdf.PdfName;
//import com.itextpdf.text.pdf.PdfPKCS7;
import com.itextpdf.text.pdf.PdfSignature;
import com.itextpdf.text.pdf.PdfSignatureAppearance;
import com.itextpdf.text.pdf.PdfStamper;
import com.itextpdf.text.pdf.PdfString;
import com.aliuken.pki.model.CertificateData;
import com.aliuken.pki.reader.AutocloseablePdfReader;
import com.itextpdf.text.pdf.security.BouncyCastleDigest;
import com.itextpdf.text.pdf.security.MakeSignature;
import com.itextpdf.text.pdf.security.PdfPKCS7;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;

public class PdfSignatureService implements SignatureService {
    private static final int ESTIMATED_CONTENT = 32768;

    @Override
    public boolean sign(CertificateData certificateData, String originFile, String destinationFile) throws Exception {
        if (certificateData != null && certificateData.privateKey() != null) {
            byte[] documentContent;
            try {
                Path path = Paths.get(originFile);
                documentContent = Files.readAllBytes(path);
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

            byte[] signatureResult = PdfSignatureService.sign(documentContent, certificateData);

            try (FileOutputStream out = new FileOutputStream(destinationFile)) {
                out.write(signatureResult);
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }

            System.out.println("pdf signed");

            return true;
        } else {
            return false;
        }
    }

    private static byte[] sign(byte[] documentContent, CertificateData certificateData) throws Exception {
        try (AutocloseablePdfReader pdfReader = new AutocloseablePdfReader(documentContent);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            // get pdfSignatureAppearance
            PdfStamper pdfStamper = PdfStamper.createSignature(pdfReader, outputStream, '\000', null, true);
            PdfSignatureAppearance pdfSignatureAppearance = PdfSignatureService.getPdfSignatureAppearance(certificateData, pdfStamper);

            // pdfSignatureAppearance.preClose
            HashMap<PdfName, Integer> exclusionSizes = new HashMap<>();
            exclusionSizes.put(PdfName.CONTENTS, Integer.valueOf(ESTIMATED_CONTENT * 2 + 2));

            pdfSignatureAppearance.preClose(exclusionSizes);

            // pdfSignatureAppearance.close
            byte[] hashBytes = PdfSignatureService.getHashBytes(pdfSignatureAppearance);
            PdfDictionary pdfDictionary = PdfSignatureService.getPdfDictionary(certificateData, hashBytes);

            pdfSignatureAppearance.close(pdfDictionary);

            outputStream.flush();

            byte[] result = outputStream.toByteArray();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private static PdfSignatureAppearance getPdfSignatureAppearance(CertificateData certificateData, PdfStamper pdfStamper) throws CertificateEncodingException {
        Date currentDate = new Date();
        Calendar currentCalendar = PdfSignatureService.getCalendarFromDate(currentDate);
        String commonName = PdfSignatureService.getCNFromPublicCertificate(certificateData.publicCertificate());
        String reason = "Digital Signature to ensure authentication, integrity and non-repudiation";
        String location = "Madrid";
        SimpleDateFormat dateFormatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss Z");
        String formattedCurrentDate = dateFormatter.format(currentDate);
        String signature = "Signed by " + commonName + "\nReason: " + reason + "\nLocation: " + location + "\nFecha: " + formattedCurrentDate;

        PdfSignature pdfSignature = new PdfSignature(PdfName.ADOBE_PPKLITE, new PdfName("adbe.pkcs7.detached"));
        pdfSignature.setReason(reason);
        pdfSignature.setLocation(location);
        pdfSignature.setDate(new PdfDate(currentCalendar));

        PdfSignatureAppearance pdfSignatureAppearance = pdfStamper.getSignatureAppearance();
        pdfSignatureAppearance.setSignDate(currentCalendar);
        pdfSignatureAppearance.setCryptoDictionary(pdfSignature);
        pdfSignatureAppearance.setLayer2Text(signature);
        pdfSignatureAppearance.setVisibleSignature(new Rectangle(100, 100, 350, 200), 1, null);

        return pdfSignatureAppearance;
    }

    private static Calendar getCalendarFromDate(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);

        return calendar;
    }

    private static String getCNFromPublicCertificate(X509Certificate publicCertificate) throws CertificateEncodingException {
        X500Name x500name = new JcaX509CertificateHolder(publicCertificate).getSubject();
        RDN cn = x500name.getRDNs(BCStyle.CN)[0];

        return IETFUtils.valueToString(cn.getFirst().getValue());
    }

    private static byte[] getHashBytes(PdfSignatureAppearance pdfSignatureAppearance) throws IOException, NoSuchAlgorithmException {
        InputStream rangeStream = pdfSignatureAppearance.getRangeStream();
        byte[] buffer = new byte[ESTIMATED_CONTENT];

        int length;
        MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
        while ((length = rangeStream.read(buffer)) > 0) {
            messageDigest.update(buffer, 0, length);
        }

        byte[] hashBytes = messageDigest.digest();
        return hashBytes;
    }

    private static PdfDictionary getPdfDictionary(CertificateData certificateData, byte[] hashBytes) throws NoSuchAlgorithmException, InvalidKeyException, NoSuchProviderException, SignatureException {
        //PdfPKCS7 pdfPKCS7 = new PdfPKCS7(certificateData.privateKey(), certificateData.certificateChain(), null, "SHA-256", null, false);
        PdfPKCS7 pdfPKCS7 = new PdfPKCS7(certificateData.privateKey(), certificateData.certificateChain(), "SHA-256", null, new BouncyCastleDigest(), false);
        //Calendar signatureCalendar = pdfSignatureAppearance.getSignDate();

        //byte[] authenticatedAttributeBytes = pdfPKCS7.getAuthenticatedAttributeBytes(hashBytes, signatureCalendar, null);
        byte[] authenticatedAttributeBytes = pdfPKCS7.getAuthenticatedAttributeBytes(hashBytes, null, null, MakeSignature.CryptoStandard.CADES);
        pdfPKCS7.update(authenticatedAttributeBytes, 0, authenticatedAttributeBytes.length);

        //byte[] pkcs7SignedDataBytes = pdfPKCS7.getEncodedPKCS7(hashBytes, signatureCalendar, null, null);
        byte[] pkcs7SignedDataBytes = pdfPKCS7.getEncodedPKCS7(hashBytes, null, null, null, MakeSignature.CryptoStandard.CADES);

        byte[] pkcs7SignedDataBytesCopy = new byte[ESTIMATED_CONTENT];
        System.arraycopy(pkcs7SignedDataBytes, 0, pkcs7SignedDataBytesCopy, 0, pkcs7SignedDataBytes.length);

        PdfString pdfString = new PdfString(pkcs7SignedDataBytesCopy).setHexWriting(true);

        PdfDictionary pdfDictionary = new PdfDictionary();
        pdfDictionary.put(PdfName.CONTENTS, pdfString);

        return pdfDictionary;
    }
}
