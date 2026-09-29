USE [TradeManagement]
GO
        CREATE TABLE [dbo].[BitcoinPaymentStatus] (
                [Id] INT IDENTITY(1, 1) NOT NULL,
                [BitcoinPaymentId] nvarchar(36) NOT NULL,
                [Timestamp] datetimeoffset(0) NOT NULL,
                [Status] VARCHAR(10) NOT NULL CHECK(
                        Status IN('pending', 'confirming', 'confirmed', 'failed')
                ),
                [Details] nvarchar(255),
                CONSTRAINT [PK_BitcoinPaymentStatus] PRIMARY KEY CLUSTERED ([Id] ASC) ON [PRIMARY],
                CONSTRAINT [FK_BitcoinPaymentStatus_BitcoinPayments] FOREIGN KEY ([BitcoinPaymentId]) REFERENCES BitcoinPayments([Id])
        )
        CREATE INDEX [IX_BitcoinPaymentStatus_Status] ON [dbo].[BitcoinPaymentStatus] ([Status])
        CREATE INDEX [IX_BitcoinPaymentStatus_PaymentId] ON [dbo].[BitcoinPaymentStatus] ([BitcoinPaymentId])
GO
